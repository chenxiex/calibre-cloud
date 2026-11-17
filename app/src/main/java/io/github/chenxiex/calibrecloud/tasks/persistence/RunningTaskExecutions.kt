package io.github.chenxiex.calibrecloud.tasks.persistence

import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** Process-local execution ownership; durable task state alone does not mean its resources are closed. */
internal class RunningTaskExecutions {
    private class Running(val job: Job) {
        @Volatile var interrupted = false
    }

    private val running = mutableMapOf<TaskId, Running>()

    fun isExecuting(id: TaskId): Boolean = synchronized(running) { id in running }

    /** The caller must persist cancellation before interrupting; a later execution checks that gate on entry. */
    fun interrupt(id: TaskId) {
        val execution = synchronized(running) { running[id]?.also { it.interrupted = true } }
        execution?.job?.cancel(CancellationException("Task cancelled"))
    }

    /**
     * Registers before starting the child. A task cancellation stops only that child, while stopping
     * the driver propagates normally. The exit hook runs after resource closure and deregistration,
     * including when the driver stops; it owns no task-specific cleanup policy.
     */
    suspend fun <T> run(id: TaskId, onCancelled: suspend () -> T, onExited: suspend () -> Unit,
        action: suspend () -> T): T = supervisorScope {
        val child = async(start = CoroutineStart.LAZY) { action() }
        val execution = Running(child)
        synchronized(running) {
            check(id !in running) { "Task already executing" }
            running[id] = execution
        }
        try {
            try { child.await() }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!execution.interrupted) throw failure
                withContext(NonCancellable) { onCancelled() }
            }
        } finally {
            withContext(NonCancellable) {
                child.cancelAndJoin()
                synchronized(running) { check(running.remove(id) === execution) }
                onExited()
            }
        }
    }
}
