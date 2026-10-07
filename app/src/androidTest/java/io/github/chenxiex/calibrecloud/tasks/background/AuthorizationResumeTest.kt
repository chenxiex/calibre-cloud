package io.github.chenxiex.calibrecloud.tasks.background

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.CalibreFixture
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real SQLite selection and queue state; source handlers are never run. */
@RunWith(AndroidJUnit4::class)
class AuthorizationResumeTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var root: File
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var startup: StartupSync
    private var localReady = true

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "authorization-resume-test-${UUID.randomUUID()}.db"
        root = File(context.cacheDir, "authorization-resume-${UUID.randomUUID()}").apply { mkdirs() }
        database = ApplicationStateDatabase(context, name)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        val handler = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.CandidateConfiguration
            override fun controls(stage: TaskStage) = TaskControls(false, false, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision = error("Not executed")
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome = error("Not executed")
        }
        startup = StartupSync(state, TaskCoordinator(queue, listOf(handler)))
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
        root.deleteRecursively()
    }

    @Test
    fun reselectingTheSameLocalLibraryContinuesTheWaitingSync() = runBlocking<Unit> {
        val location = LibraryLocation.Local("test.documents", "library")
        val identity = importLibrary(location)
        val waiting = waitingSync()
        val reselected = state.select(location)
        assertEquals(identity, reselected.identity)
        resume()
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(waiting)!!.record.state)
        val replacement = queue.list().map { it.record }.single { it.id != waiting }
        assertEquals(reselected.token, (replacement.submission.request as TaskRequest.CandidateConfiguration).context.selectionToken)
        assertEquals(TaskOrigin.MANUAL_SYNC, replacement.originalOrigin)
        assertEquals(TaskState.Queued, replacement.state)
        assertEquals(reselected.token, state.current()!!.token)
    }

    @Test
    fun selectingAnotherDirectoryCancelsTheObsoleteSyncWithoutSubmitting() = runBlocking<Unit> {
        importLibrary(LibraryLocation.Local("test.documents", "library"))
        val waiting = waitingSync()
        val other = state.select(LibraryLocation.Local("test.documents", "other"))
        resume()
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(waiting)!!.record.state)
        assertEquals(1, queue.list().size)
        assertEquals(other.token, state.current()!!.token)
    }

    @Test
    fun currentSelectionKeepsItsTaskAndTokenAndMissingAuthorizationChangesNothing() = runBlocking<Unit> {
        val location = LibraryLocation.Local("test.documents", "library")
        importLibrary(location)
        val waiting = waitingSync()
        val token = state.current()!!.token
        localReady = false
        resume()
        assertTrue(queue.get(waiting)!!.record.state is TaskState.Waiting)
        localReady = true
        resume()
        // The equivalent request is the same task; cancelling it would revoke the current token.
        assertEquals(listOf(waiting), queue.list().map { it.record.id })
        assertTrue(queue.get(waiting)!!.record.state is TaskState.Waiting)
        assertEquals(token, state.current()!!.token)
    }

    private suspend fun importLibrary(location: LibraryLocation.Local) =
        requireNotNull(MetadataRepository(database, state, File(root, "imports"), Dispatchers.IO)
            .importSnapshot(state.select(location).token, CalibreFixture.create(File(root, "${UUID.randomUUID()}.db"))))

    private suspend fun waitingSync(): TaskId {
        assertTrue(startup.manualSync())
        val id = queue.list().single().record.id
        queue.update(id) { it.copy(record = it.record.copy(
            state = TaskState.Waiting(FrozenSet(listOf(WaitingReason.DIRECTORY_AUTHORIZATION))))) }
        return id
    }

    private suspend fun resume() = AuthorizationResume(queue, state, oneDriveReady = { false }, localReady = { localReady },
        resubmitSync = startup::resubmit, browse = { error("No OneDrive browse in local fixtures") }).resume()
}
