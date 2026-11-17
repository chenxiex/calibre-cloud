package io.github.chenxiex.calibrecloud.tasks.persistence

import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RunningTaskExecutionsTest {
    @Test
    fun exitHookRunsAfterSuccessfulOrFailedExecutionWithoutChangingItsOutcome() = runTest {
        val executions = RunningTaskExecutions()
        for (fail in listOf(false, true)) {
            val task = TaskId(UUID.randomUUID())
            val failure = IllegalStateException("Fixture failure")
            var exited = false
            try {
                val result = executions.run(task,
                    onCancelled = { error("No cancellation requested") },
                    onExited = { assertFalse(executions.isExecuting(task)); exited = true },
                ) {
                    if (fail) throw failure
                    "complete"
                }
                assertFalse(fail)
                assertEquals("complete", result)
            } catch (caught: IllegalStateException) {
                assertTrue(fail)
                assertEquals(failure.javaClass, caught.javaClass)
                assertEquals(failure.message, caught.message)
            }
            assertTrue(exited)
        }
    }

    @Test
    fun cancellationStillWinsWhenResourceClosureThrowsIoFailure() = runTest {
        val executions = RunningTaskExecutions()
        val task = TaskId(UUID.randomUUID())
        var result: String? = null
        var exited = false
        val driver = launch {
            result = executions.run(task,
                onCancelled = { "cancelled" },
                onExited = { exited = true },
            ) {
                try { awaitCancellation() }
                finally { throw IOException("Fixture close failure") }
            }
        }
        runCurrent()
        executions.interrupt(task)
        driver.join()
        assertFalse(driver.isCancelled)
        assertEquals("cancelled", result)
        assertTrue(exited)
        assertFalse(executions.isExecuting(task))
    }

    @Test
    fun cancellationWaitsForResourceClosureThenCallsExitHookAndKeepsDriverAlive() = runTest {
        val executions = RunningTaskExecutions()
        val task = TaskId(UUID.randomUUID())
        val closing = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val driver = launch {
            val result = executions.run(task,
                onCancelled = { order.add("cancelled"); false },
                onExited = {
                    assertFalse(executions.isExecuting(task))
                    order.add("exited")
                },
            ) {
                try { awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        closing.complete(Unit)
                        releaseClose.await()
                        order.add("closed")
                    }
                }
            }
            assertFalse(result)
            order.add("next")
        }
        runCurrent()
        assertTrue(executions.isExecuting(task))
        executions.interrupt(task)
        closing.await()
        assertTrue(executions.isExecuting(task))
        assertTrue(order.isEmpty())
        releaseClose.complete(Unit)
        driver.join()
        assertEquals(listOf("closed", "cancelled", "exited", "next"), order)
    }

    @Test
    fun stoppingDriverPropagatesAndStillCallsExitHookWithoutTreatingItAsUserCancel() = runTest {
        val executions = RunningTaskExecutions()
        val task = TaskId(UUID.randomUUID())
        var cancelledByUser = false
        var closed = false
        var exited = false
        val driver = launch(start = CoroutineStart.UNDISPATCHED) {
            executions.run(task,
                onCancelled = { cancelledByUser = true },
                onExited = { assertTrue(closed); exited = true },
            ) {
                try { awaitCancellation() } finally { closed = true }
            }
        }
        runCurrent()
        driver.cancelAndJoin()
        assertTrue(driver.isCancelled)
        assertFalse(cancelledByUser)
        assertTrue(exited)
        assertFalse(executions.isExecuting(task))
    }
}
