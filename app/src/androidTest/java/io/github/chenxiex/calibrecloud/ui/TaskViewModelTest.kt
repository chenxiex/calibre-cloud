package io.github.chenxiex.calibrecloud.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskSubmission
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Real private queue and UI scope; no worker, source access or production queue modification. */
@RunWith(AndroidJUnit4::class)
class TaskViewModelTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var application: Application
    private lateinit var database: ApplicationStateDatabase
    private lateinit var databaseName: String
    private lateinit var queue: DurableTaskQueue
    private lateinit var model: TaskViewModel
    private val store = ViewModelStore()
    private var wakeFails = false

    @Before
    fun setUp() = runBlocking<Unit> {
        application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        databaseName = "task-ui-test-${UUID.randomUUID()}.db"
        database = ApplicationStateDatabase(application, databaseName)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        val state = ApplicationStateRepository(database, PrivateBookFiles(application.filesDir), Dispatchers.IO)
        val identity = LibraryIdentity(LibraryId(UUID.randomUUID()),
            LibraryLocation.Local("test.documents", "fixture-root"), UUID.randomUUID())
        assertTrue(state.bindValidated(state.select(identity.location).token, identity))
        queue.submit(TaskSubmission(TaskRequest.MetadataSync(identity.id), TaskOrigin.MANUAL_SYNC))
        // Without a composition, every idle wait blocks for the rule's two-second root timeout.
        compose.setContent {}
        compose.runOnIdle {
            model = TaskViewModel(queue, { false }, {}, { if (wakeFails) throw IOException() },
                { true }, application, { false })
            store.put("tasks", model)
        }
    }

    @After
    fun tearDown() {
        compose.runOnIdle { store.clear() }
        database.close()
        application.deleteDatabase(databaseName)
    }

    @Test
    fun rejectedControlStaysVisibleThroughRefreshAndNextControlClearsIt() = runBlocking<Unit> {
        val real = queue.list().single().record
        val missing = real.copy(id = TaskId(UUID.randomUUID()))
        compose.runOnIdle { model.control(missing, TaskControl.CANCEL) }
        compose.waitUntil(10_000) { model.operationFailed }
        assertTrue(queue.control(real.id, TaskControl.CANCEL))
        queue.submit(real.submission)
        val next = queue.list().first { it.record.state !is TaskState.Finished }.record
        compose.runOnIdle { model.setVisible(true) }
        compose.waitUntil(10_000) { model.records.size == 2 && model.settingsLoaded }
        compose.runOnIdle {
            assertTrue(model.operationFailed)
            assertFalse(model.failed)
            model.control(next, TaskControl.CANCEL)
        }
        compose.waitUntil(10_000) {
            !model.operationFailed && model.records.any { it.id == next.id && it.state is TaskState.Finished }
        }
    }

    @Test
    fun controlExceptionStaysVisibleThroughSuccessfulBackgroundRefresh() = runBlocking<Unit> {
        val real = queue.list().single().record
        compose.runOnIdle {
            wakeFails = true
            model.control(real, TaskControl.CANCEL)
        }
        compose.waitUntil(10_000) { model.operationFailed }
        queue.submit(real.submission)
        compose.runOnIdle { model.setVisible(true) }
        compose.waitUntil(10_000) { model.records.size == 2 && model.settingsLoaded }
        compose.runOnIdle {
            assertTrue(model.operationFailed)
            assertFalse(model.failed)
        }
    }
}
