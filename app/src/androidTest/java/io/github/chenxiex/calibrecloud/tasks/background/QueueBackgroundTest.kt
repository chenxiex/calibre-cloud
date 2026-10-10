package io.github.chenxiex.calibrecloud.tasks.background

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real SQLite with explicitly injected wakeup/handler fixtures; not evidence of OS background scheduling. */
@RunWith(AndroidJUnit4::class)
class QueueBackgroundTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var queue: DurableTaskQueue
    private lateinit var identity: LibraryIdentity

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "background-queue-${UUID.randomUUID()}.db"
        reopen()
        identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", "background-fixture"), UUID.randomUUID())
        val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        assertTrue(state.bindValidated(state.select(identity.location).token, identity))
    }

    @After
    fun tearDown() {
        queue.onWake = {}
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun durableCreateReuseAndPromotionWakeButRejectedSubmissionsDoNot() = runBlocking<Unit> {
        val wakes = AtomicInteger()
        queue.onWake = {
            // A separate read succeeds and sees the committed record before dispatch is requested.
            assertEquals(1, queue.list().size)
            wakes.incrementAndGet()
        }
        val automatic = cover(1, TaskOrigin.VISIBLE_COVER)
        val id = created(automatic)
        assertEquals(SubmissionResult.Reused(id), queue.submit(automatic))
        assertEquals(id, (queue.submit(automatic.copy(origin = TaskOrigin.USER_OPEN)) as SubmissionResult.Promoted).taskId)
        assertEquals(SubmissionResult.Reused(id), queue.submit(automatic.copy(origin = TaskOrigin.USER_OPEN)))
        assertEquals(4, wakes.get())

        val unsupported = TaskCoordinator(queue, emptyList())
        assertTrue(unsupported.submit(cover(2, TaskOrigin.USER_DOWNLOAD)) is SubmissionResult.Rejected)
        assertTrue(queue.submit(TaskSubmission(TaskRequest.CoverLoad(BookKey(LibraryId(UUID.randomUUID()), 1, UUID.randomUUID())),
            TaskOrigin.USER_OPEN)) is SubmissionResult.Rejected)
        assertEquals(4, wakes.get())
        val supported = TaskCoordinator(queue, listOf(FixtureHandler { _, _ -> StageOutcome.Complete() }))
        supported.requestRun()
        assertEquals(5, wakes.get())
    }

    @Test
    fun retryAndResumeWakeOnlyAfterAcceptedControlIsCommitted() = runBlocking<Unit> {
        val id = created(cover(1, TaskOrigin.USER_DOWNLOAD))
        TaskCoordinator(queue, listOf(FixtureHandler { _, _ ->
            StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.LOCAL_IO)))
        })).drain()
        val wakes = AtomicInteger()
        queue.onWake = {
            assertEquals(TaskState.Queued, queue.get(id)!!.record.state)
            wakes.incrementAndGet()
        }
        assertTrue(queue.control(id, TaskControl.RETRY))
        assertEquals(1, wakes.get())
        assertFalse(queue.control(id, TaskControl.RETRY))
        assertEquals(1, wakes.get())

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = TaskCoordinator(queue, listOf(FixtureHandler { _, execution ->
            entered.complete(Unit)
            release.await()
            execution.checkControl()
            StageOutcome.Complete()
        }))
        val running = launch(Dispatchers.Default) { coordinator.drain() }
        withTimeout(10_000) { entered.await() }
        queue.onWake = { wakes.incrementAndGet() }
        assertTrue(queue.control(id, TaskControl.PAUSE))
        release.complete(Unit)
        withTimeout(10_000) { running.join() }
        assertTrue(queue.get(id)!!.record.state is TaskState.Paused)
        queue.onWake = {
            assertEquals(TaskState.Queued, queue.get(id)!!.record.state)
            wakes.incrementAndGet()
        }
        assertTrue(queue.control(id, TaskControl.RESUME))
        assertEquals(3, wakes.get())
        assertFalse(queue.control(id, TaskControl.RESUME))
        assertFalse(queue.control(TaskId(UUID.randomUUID()), TaskControl.RETRY))
        assertEquals(3, wakes.get())
    }

    @Test
    fun submissionAfterFinalEmptyClaimRunsBeforePreviousWorkerExits() = runBlocking<Unit> {
        val firstDrained = CompletableDeferred<Unit>()
        val letFirstWorkerExit = CompletableDeferred<Unit>()
        val secondDrained = CompletableDeferred<Unit>()
        val wakeNumber = AtomicInteger()
        val coordinator = TaskCoordinator(queue, listOf(FixtureHandler { _, _ -> StageOutcome.Complete() }))
        // Each accepted wake appends another driver. The old worker is deliberately held after
        // drain's final empty claim, reproducing the submission/worker-completion race deterministically.
        queue.onWake = {
            val number = wakeNumber.incrementAndGet()
            launch(Dispatchers.Default) {
                coordinator.drain()
                if (number == 1) {
                    firstDrained.complete(Unit)
                    letFirstWorkerExit.await()
                } else secondDrained.complete(Unit)
            }
        }
        try {
            val first = created(cover(1, TaskOrigin.USER_DOWNLOAD))
            withTimeout(10_000) { firstDrained.await() }
            assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(first)!!.record.state)
            val second = created(cover(2, TaskOrigin.USER_DOWNLOAD))
            withTimeout(10_000) { secondDrained.await() }
            assertFalse(letFirstWorkerExit.isCompleted)
            assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(second)!!.record.state)
            assertEquals(2, wakeNumber.get())
        } finally {
            letFirstWorkerExit.complete(Unit)
        }
    }

    @Test
    fun injectedWorkerAndForegroundDrainShareSingleExecutorAndReevaluatePriority() = runBlocking<Unit> {
        val first = created(cover(1, TaskOrigin.VISIBLE_COVER))
        val second = created(cover(2, TaskOrigin.VISIBLE_COVER))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val executed = mutableListOf<TaskId>()
        val handler = FixtureHandler { entry, _ ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, count) }
            try {
                executed.add(entry.record.id)
                if (entry.record.id == first) {
                    entered.complete(Unit)
                    release.await()
                }
                StageOutcome.Complete()
            } finally { active.decrementAndGet() }
        }
        val foreground = TaskCoordinator(queue, listOf(handler))
        val worker = TaskCoordinator(queue, listOf(handler))
        val foregroundRun = launch(Dispatchers.Default) { foreground.drain() }
        withTimeout(10_000) { entered.await() }
        queue.onWake = { launch(Dispatchers.Default) { worker.drain() } }
        val high = created(cover(3, TaskOrigin.USER_DOWNLOAD))
        assertEquals(first, queue.list().single { it.record.state is TaskState.Running }.record.id)
        release.complete(Unit)
        withTimeout(10_000) { foregroundRun.join() }
        // The injected worker is a child of this runBlocking and must finish before the test returns.
        assertEquals(1, maximum.get())
        assertEquals(listOf(first, high, second), executed)
        assertTrue(queue.list().all { it.record.state == TaskState.Finished(TaskResult.Completed) })
    }

    @Test
    fun reopenedQueueRecoversInterruptedWorkWithoutLosingPriorityOrder() = runBlocking<Unit> {
        val interrupted = created(cover(1, TaskOrigin.VISIBLE_COVER))
        val entered = CompletableDeferred<Unit>()
        val neverFinish = CompletableDeferred<Unit>()
        val checkpoint = RecoveryCheckpoint(UUID.randomUUID(), FileVersion(BackendKind.LOCAL, "fixture-version"))
        val original = TaskCoordinator(queue, listOf(FixtureHandler { _, execution ->
            execution.checkpoint(checkpoint)
            entered.complete(Unit)
            neverFinish.await()
            StageOutcome.Complete()
        }))
        val running = launch(Dispatchers.Default) { original.drain() }
        withTimeout(10_000) { entered.await() }
        running.cancelAndJoin()
        val high = created(cover(2, TaskOrigin.USER_DOWNLOAD))
        val later = created(cover(3, TaskOrigin.VISIBLE_COVER))
        database.close()
        reopen()
        val executionOrder = mutableListOf<TaskId>()
        val recovered = mutableListOf<TaskId>()
        val handler = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.CoverLoad
            override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision {
                recovered.add(entry.record.id)
                assertEquals(interrupted, entry.record.id)
                assertEquals(checkpoint, entry.checkpoint)
                return RecoveryDecision(entry.stage, entry.checkpoint)
            }
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                return if (entry.stage == TaskStage.COVER_TRANSFER) {
                    executionOrder.add(entry.record.id)
                    StageOutcome.Advance(TaskStage.COVER_PUBLISH)
                } else StageOutcome.Complete()
            }
        }
        val worker = TaskCoordinator(queue, listOf(handler))
        worker.drain()
        assertEquals(listOf(high, interrupted, later), executionOrder)
        assertEquals(listOf(interrupted), recovered)
        assertTrue(queue.list().all { it.record.state == TaskState.Finished(TaskResult.Completed) })
    }

    private class FixtureHandler(private val body: suspend (QueueEntry, TaskExecution) -> StageOutcome) : TaskHandler {
        override fun supports(request: TaskRequest) = request is TaskRequest.CoverLoad
        override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
        override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(entry.stage, entry.checkpoint)
        override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
            if (entry.stage == TaskStage.COVER_PUBLISH) return StageOutcome.Complete()
            val outcome = body(entry, execution)
            return if (outcome is StageOutcome.Complete) StageOutcome.Advance(TaskStage.COVER_PUBLISH) else outcome
        }
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, name)
        queue = DurableTaskQueue(database, Dispatchers.IO)
    }

    private fun cover(number: Long, origin: TaskOrigin) = TaskSubmission(TaskRequest.CoverLoad(
        BookKey(identity.id, number, UUID.nameUUIDFromBytes("background-$number".toByteArray()))), origin)

    private suspend fun created(submission: TaskSubmission) = (queue.submit(submission) as SubmissionResult.Created).taskId
}
