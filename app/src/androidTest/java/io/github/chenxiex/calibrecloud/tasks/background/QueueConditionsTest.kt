package io.github.chenxiex.calibrecloud.tasks.background

import io.github.chenxiex.calibrecloud.state.selectSignedIn
import io.github.chenxiex.calibrecloud.storage.SourcePolicies
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Controlled OS connectivity boundary with real SQLite; no physical disconnection or source access. */
@RunWith(AndroidJUnit4::class)
class QueueConditionsTest {
    @Test
    fun offlineConditionsDistinguishStoredBackendsAndCandidateContexts() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "network-conditions-${UUID.randomUUID()}.db"
        val database = ApplicationStateDatabase(context, name)
        try {
            val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
            val local = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", "fixture"), UUID.randomUUID())
            val cloud = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.OneDrive("account", "drive", "root"), UUID.randomUUID())
            assertTrue(state.bindValidated(state.select(local.location).token, local))
            assertTrue(state.bindValidated(state.select(cloud.location).token, cloud))
            var connected = false
            val conditions = QueueConditions(context, database, SourcePolicies.sources) { connected }
            assertTrue(conditions.waiting(record(check(local.id))).isEmpty())
            assertEquals(setOf(WaitingReason.NETWORK), conditions.waiting(record(check(cloud.id))))
            for (backend in BackendKind.entries) {
                val candidate = TaskRequest.CandidateConfiguration(CandidateContext(UUID.randomUUID(), backend, UUID.randomUUID()), "fixture")
                assertEquals(if (backend == BackendKind.ONEDRIVE) setOf(WaitingReason.NETWORK) else emptySet<WaitingReason>(),
                    conditions.waiting(record(candidate)))
            }
            connected = true
            assertTrue(conditions.waiting(record(check(cloud.id))).isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun offlineStartupPersistsOneWaitingTaskAndManualPromotesBeforeConnectivityReturns() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "offline-startup-${UUID.randomUUID()}.db"
        val database = ApplicationStateDatabase(context, name)
        try {
            val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
            val session = UUID.randomUUID()
            state.selectSignedIn(LibraryLocation.OneDrive("account", "drive", "root"), session)
            state.setStartupEnabled(true)
            var connected = false
            var calls = 0
            val queue = DurableTaskQueue(database, Dispatchers.IO)
            val conditions = QueueConditions(context, database, SourcePolicies.sources) { connected }
            val handler = object : TaskHandler {
                override fun supports(request: TaskRequest) = request is TaskRequest.CandidateConfiguration
                override fun controls(stage: TaskStage) = TaskControls(false, true, false, false)
                override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(entry.stage, null)
                override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                    calls++
                    return StageOutcome.Fail(TaskError.Source(io.github.chenxiex.calibrecloud.storage.api.StorageError(
                        io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind.LOGIN_REQUIRED)))
                }
            }
            val coordinator = TaskCoordinator(queue, listOf(handler), conditions::waiting)
            val startup = StartupSync(state, coordinator, io.github.chenxiex.calibrecloud.tasks.TestAuthorizations.of(state, { session }))
            val task = requireNotNull(startup.onMainOpened())
            val selected = state.current()
            coordinator.drain()
            assertEquals(0, calls)
            assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), queue.get(task)!!.record.state)
            assertNull(startup.onMainOpened())
            assertTrue(startup.manualSync())
            assertEquals(1, queue.list().size)
            assertEquals(TaskPriority.HIGH, queue.get(task)!!.record.scheduling.priority)
            assertEquals(TaskOrigin.STARTUP_SYNC, queue.get(task)!!.record.originalOrigin)
            assertEquals(selected, state.current())
            connected = true
            coordinator.drain()
            assertEquals(1, calls)
            assertTrue(queue.get(task)!!.record.state is TaskState.Finished)
            assertEquals(selected, state.current())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun record(request: TaskRequest) = TaskRecord(TaskId(UUID.randomUUID()), TaskSubmission(request, TaskOrigin.STARTUP_SYNC),
        SchedulingPosition(TaskPriority.LOW, QueueSequence(1)), state = TaskState.Queued,
        controls = TaskControls(false, true, false, false))

    private fun check(library: LibraryId) = TaskRequest.FormatCheck(CopyKey(BookKey(library, 1, UUID.randomUUID()), BookFormat.parse("EPUB")))
}
