package io.github.chenxiex.calibrecloud.tasks

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Durable cancellation of a handler that does not reach another cooperative boundary. */
@RunWith(AndroidJUnit4::class)
class TaskCancellationTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var queue: DurableTaskQueue
    private lateinit var library: LibraryIdentity

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "task-cancellation-${UUID.randomUUID()}.db"
        database = ApplicationStateDatabase(context, name)
        val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        library = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", "cancellation"), UUID.randomUUID())
        assertTrue(state.bindValidated(state.select(library.location).token, library))
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun cancelPersistsTerminalStateAndDriverContinuesAfterHandlerHasExited() = runBlocking<Unit> {
        val first = (queue.submit(request(1)) as SubmissionResult.Created).taskId
        val second = (queue.submit(request(2)) as SubmissionResult.Created).taskId
        val started = CompletableDeferred<Unit>()
        var closed = false
        var stopped = false
        var hookCalled = false
        queue.onTaskExited = { id ->
            if (id == first) {
                assertTrue(closed)
                assertTrue(stopped)
                assertFalse(queue.isExecuting(id))
                assertEquals(TaskState.Finished(TaskResult.Cancelled), queue.get(id)!!.record.state)
                hookCalled = true
            }
        }
        val handler = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.FormatCheck
            override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(entry.stage, entry.checkpoint)
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                if (entry.record.id == first) {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { closed = true }
                }
                assertTrue(hookCalled)
                return StageOutcome.Complete()
            }
            override suspend fun stopped(entry: QueueEntry) { stopped = true }
        }
        val driver = launch(Dispatchers.Default) { TaskCoordinator(queue, listOf(handler)).drain() }
        started.await()
        assertTrue(queue.control(first, TaskControl.CANCEL))
        driver.join()
        assertFalse(driver.isCancelled)
        assertTrue(hookCalled)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(second)!!.record.state)
        database.close()
        database = ApplicationStateDatabase(context, name)
        assertEquals(TaskState.Finished(TaskResult.Cancelled), DurableTaskQueue(database, Dispatchers.IO).get(first)!!.record.state)
    }

    @Test
    fun lateProgressAfterRevocationKeepsTerminalStateAndRetainsCleanupEvidence() = runBlocking<Unit> {
        val first = (queue.submit(request(1)) as SubmissionResult.Created).taskId
        val second = (queue.submit(request(2)) as SubmissionResult.Created).taskId
        assertEquals(first, queue.claim(0, { emptySet() }, { true })!!.record.id)
        DurableTaskQueue.revoke(database.writableDatabase, first)
        val checkpoint = RecoveryCheckpoint(UUID.randomUUID(), FileVersion(BackendKind.LOCAL, "fixture"))
        val updated = queue.update(first) { entry -> entry.copy(checkpoint = checkpoint,
            record = entry.record.copy(state = TaskState.Running(entry.stage), controls = TaskControls(true, true, false, false))) }
        assertEquals(TaskState.Finished(TaskResult.Cancelled), updated.record.state)
        assertFalse(updated.record.controls.canCancel)
        assertEquals(checkpoint, queue.get(first)!!.checkpoint)
        assertEquals(TaskControl.CANCEL, queue.boundaryControl(first))
        assertEquals(second, queue.claim(0, { emptySet() }, { true })!!.record.id)
    }

    @Test
    fun failedCancellationCleanupWithoutCheckpointIsRetriedWithoutBlockingOtherTasks() = runBlocking<Unit> {
        val first = (queue.submit(request(1)) as SubmissionResult.Created).taskId
        val second = (queue.submit(request(2)) as SubmissionResult.Created).taskId
        val started = CompletableDeferred<Unit>()
        var cleanupCalls = 0
        val handler = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.FormatCheck
            override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(entry.stage, entry.checkpoint)
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                if (entry.record.id == first) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                return StageOutcome.Complete()
            }
            override suspend fun stopped(entry: QueueEntry) {
                cleanupCalls++
                if (cleanupCalls == 1) throw IOException("Fixture deletion failure")
            }
        }
        val coordinator = TaskCoordinator(queue, listOf(handler))
        val driver = launch(Dispatchers.Default) { coordinator.drain() }
        started.await()
        assertTrue(queue.control(first, TaskControl.CANCEL))
        driver.join()
        assertEquals(TaskState.Finished(TaskResult.Cancelled), queue.get(first)!!.record.state)
        assertTrue(queue.get(first)!!.recoveryRequired)
        assertNull(queue.get(first)!!.checkpoint)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(second)!!.record.state)
        assertEquals(1, cleanupCalls)
        assertEquals(listOf(second), queue.pruneFinished(database.writableDatabase, keep = 0))
        assertNotNull(queue.get(first))
        coordinator.drain()
        assertEquals(2, cleanupCalls)
        assertFalse(queue.get(first)!!.recoveryRequired)
    }

    private fun request(number: Long) = TaskSubmission(TaskRequest.FormatCheck(CopyKey(
        BookKey(library.id, number, UUID.randomUUID()), BookFormat.parse("EPUB"))), TaskOrigin.USER_DOWNLOAD)
}
