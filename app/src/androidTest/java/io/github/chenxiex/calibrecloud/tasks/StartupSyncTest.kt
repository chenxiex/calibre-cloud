package io.github.chenxiex.calibrecloud.tasks

import io.github.chenxiex.calibrecloud.state.StateSchemaHistory
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.background.StartupSync
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Android SQLite fixtures; source handlers are never run. */
@RunWith(AndroidJUnit4::class)
class StartupSyncTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var startup: StartupSync

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "startup-test-${UUID.randomUUID()}.db"
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun defaultDisabledAndFirstOpeningConsumesOpportunity() = runBlocking<Unit> {
        state.select(LibraryLocation.Local("test.documents", "fixture-root"))
        assertFalse(startup.startupEnabled())
        assertNull(startup.onMainOpened())
        startup.setStartupEnabled(true)
        assertNull(startup.onMainOpened())
        assertTrue(queue.list().isEmpty())
        // A new process gets a new gate, preserving the setting and current root.
        database.close()
        reopen()
        assertTrue(startup.startupEnabled())
        assertTrue(queue.list().isEmpty()) // Creating dependencies for a worker submits nothing.
        assertNotNull(startup.onMainOpened())
        assertEquals(TaskOrigin.STARTUP_SYNC, queue.list().single().record.originalOrigin)
    }

    @Test
    fun missingConfigurationConsumesOpeningAndManualStillWorks() = runBlocking<Unit> {
        startup.setStartupEnabled(true)
        assertNull(startup.onMainOpened())
        state.select(LibraryLocation.Local("test.documents", "fixture-root"))
        assertNull(startup.onMainOpened())
        assertTrue(startup.manualSync())
        assertEquals(TaskOrigin.MANUAL_SYNC, queue.list().single().record.originalOrigin)
    }

    @Test
    fun concurrentMainOpeningsSubmitOneLowPriorityRequestAndManualPromotesIt() = runBlocking<Unit> {
        val selected = state.select(LibraryLocation.Local("test.documents", "fixture-root"))
        startup.setStartupEnabled(true)
        val results = coroutineScope {
            (1..12).map { async(Dispatchers.Default) { startup.onMainOpened() } }.awaitAll()
        }
        assertEquals(1, results.count { it != null })
        val automatic = queue.list().single().record
        assertEquals(TaskPriority.LOW, automatic.scheduling.priority)
        val request = automatic.submission.request as TaskRequest.CandidateConfiguration
        assertEquals(CandidateContext(selected.token, selected.backend, selected.token), request.context)
        assertEquals(TaskRequest.CandidateConfiguration.LIBRARY_SYNC, request.operation)
        assertTrue(startup.manualSync())
        val promoted = queue.list().single().record
        assertEquals(automatic.id, promoted.id)
        assertEquals(TaskOrigin.STARTUP_SYNC, promoted.originalOrigin)
        assertEquals(TaskOrigin.MANUAL_SYNC, promoted.effectiveOrigin)
        assertEquals(TaskPriority.HIGH, promoted.scheduling.priority)
        assertNull(startup.onMainOpened())
    }

    @Test
    fun oneDriveUsesPersistedSessionAndSameManualRequest() = runBlocking<Unit> {
        val session = UUID.randomUUID()
        val location = LibraryLocation.OneDrive("test-account", "test-drive", "test-root")
        val candidate = state.beginCandidate(location.backend, session)
        assertTrue(state.resolveCandidate(candidate, location))
        startup.setStartupEnabled(true)
        val id = startup.onMainOpened()
        assertNotNull(id)
        val request = queue.list().single().record.submission.request as TaskRequest.CandidateConfiguration
        assertEquals(candidate, request.context)
        assertEquals(TaskRequest.CandidateConfiguration.LIBRARY_SYNC, request.operation)
        assertTrue(startup.manualSync())
        assertEquals(id, queue.list().single().record.id)
    }

    @Test
    fun versionFiveMigrationRetainsSelectionAndDefaultsOff() = runBlocking<Unit> {
        val selected = state.select(LibraryLocation.Local("test.documents", "fixture-root"))
        StateSchemaHistory.downgrade(database.writableDatabase, 5)
        database.close()
        reopen()
        assertEquals(selected, state.current())
        assertFalse(startup.startupEnabled())
        assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, name)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        val handler = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.CandidateConfiguration
            override fun controls(stage: TaskStage) = TaskControls(false, false, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision =
                error("Startup must not execute source handlers")
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome =
                error("Startup must not execute source handlers")
        }
        startup = StartupSync(state, TaskCoordinator(queue, listOf(handler)), TestAuthorizations.of(state))
    }
}
