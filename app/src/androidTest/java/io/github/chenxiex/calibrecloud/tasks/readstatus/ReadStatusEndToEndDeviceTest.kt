package io.github.chenxiex.calibrecloud.tasks.readstatus

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.api.LibrarySource
import io.github.chenxiex.calibrecloud.storage.api.LibrarySources
import io.github.chenxiex.calibrecloud.storage.api.PushJournal
import io.github.chenxiex.calibrecloud.storage.api.PushOutcome
import io.github.chenxiex.calibrecloud.storage.api.SourceFailure
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.local.AndroidLocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.local.AndroidSnapshotValidator
import io.github.chenxiex.calibrecloud.storage.local.LocalDatabaseCommit
import io.github.chenxiex.calibrecloud.storage.local.LocalDocument
import io.github.chenxiex.calibrecloud.storage.local.LocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.local.LocalLibrarySource
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackend
import io.github.chenxiex.calibrecloud.tasks.api.PendingRead
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.tasks.background.BackgroundTasks
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import io.github.chenxiex.calibrecloud.tasks.sync.LibrarySyncTaskHandler
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 end-to-end paths against the current library's real source (local test copy or OneDrive test
 * library), through the production service, queue, handlers and backends. Opt-in with `-e e2eWrite true`
 * only after the user has named the dedicated test library, it is synced and a valid read column is
 * selected; never run against a real library. Each path needs its own argument as well:
 *
 * - `e2eSyncFailure`: the sync after a real push fails (a test-side failure injected at the source
 *   interface, standing in for a lost connection); retrying the sync must not write again.
 * - `e2eKill=arm` / `e2eKill=verify`: a real process death during the push. `arm` blocks the push at
 *   a point the agent then kills the process with SIGKILL (local: between the two renames, so
 *   `metadata.db` is missing; OneDrive: after Graph accepted the upload, before the task completed)
 *   and records the version the push produced in `phase4-step07/kill.json`. After the app was
 *   reopened normally and its queue reran the task, `verify` checks that the source still holds
 *   exactly that version: the rerun finished the push and wrote nothing again.
 * - `e2eTwoLibraries`: a local and a OneDrive library are both listed; writes to the book with the same
 *   numeric ID in each stay apart.
 *
 * Logs from this test carry task IDs, book numbers and outcome classes only.
 */
@RunWith(AndroidJUnit4::class)
class ReadStatusEndToEndDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
    private val arguments = InstrumentationRegistry.getArguments()
    private val exports = File(context.getExternalFilesDir(null), "phase4-step07")
    private val killRecord = File(exports, "kill.json")

    @Before fun requireExplicitOptIn() {
        assumeTrue("Writing a source requires a user-named test library and explicit opt-in", arguments.getString("e2eWrite") == "true")
        assertEquals("Acceptance requires the independent debug application", "io.github.chenxiex.calibrecloud.debug", context.packageName)
        exports.mkdirs()
    }

    @Test fun aSyncFailingAfterThePushIsRetriedWithoutWritingAgain() = runBlocking<Unit> {
        assumeTrue(arguments.getString("e2eSyncFailure") == "true")
        withTimeout(600_000) {
            val location = location()
            val (key, before) = book(4)
            val target = before != true
            val production = dependencies.librarySources.of(location.backend)
            var pushed: FileVersion? = null
            val failing = object : LibrarySource by production {
                override suspend fun pushDatabase(location: LibraryLocation, staged: File, stagedSha256: String, base: FileVersion,
                    journal: PushJournal): PushOutcome =
                    production.pushDatabase(location, staged, stagedSha256, base, journal).also { if (it is PushOutcome.Pushed) pushed = it.version }

                override suspend fun acquireSnapshot(location: LibraryLocation, candidateId: UUID, unchangedVersion: FileVersion?,
                    control: suspend () -> Unit) =
                    // The connection is lost right after the push: the sync that follows cannot read the source.
                    if (pushed != null) throw SourceFailure(StorageErrorKind.LOCAL_IO)
                    else production.acquireSnapshot(location, candidateId, unchangedVersion, control)
            }
            val sync = isolated(failing) { coordinator ->
                val write = submit(key, target)
                coordinator.drain()
                val entry = dependencies.taskQueue.get(write)!!
                assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
                val sync = entry.followUp!!
                val failed = dependencies.taskQueue.get(sync)!!.record.state
                assertTrue("The sync after the push fails: $failed", failed is TaskState.Finished && failed.result is TaskResult.Failed)
                // The pending mark ends with the sync; the page keeps the import from before the push.
                assertNull(pending(key))
                assertEquals(before, importedRead(key))
                Log.i(TAG, "sync_failure write=${write.value} sync=${sync.value} pushed=${pushed != null}")
                sync
            }
            val written = requireNotNull(pushed)
            assertEquals(written, version(location))

            assertTrue(dependencies.taskQueue.control(sync, TaskControl.RETRY))
            dependencies.taskCoordinator.drain()
            awaitFinished(sync)
            assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(sync)!!.record.state)
            assertEquals(target, importedRead(key))
            // The retried sync only read: the source is still exactly what the push produced.
            assertEquals(written, version(location))
        }
    }

    @Test fun aPushStoppedByAProcessKillIsArmed() = runBlocking<Unit> {
        assumeTrue(arguments.getString("e2eKill") == "arm")
        val location = location()
        val (key, before) = book(5)
        val target = before != true
        killRecord.delete()
        fun ready(write: TaskId, version: String) {
            killRecord.writeText(JSONObject().put("task", write.value.toString()).put("book", key.sourceId)
                .put("target", target).put("version", version).toString())
            Log.i(TAG, "READY_TO_KILL task=${write.value} book=${key.sourceId} target=$target")
            // Hold the push here until the agent kills the process.
            Thread.sleep(600_000)
            fail("The process was not killed")
        }
        var write: TaskId? = null
        val source: LibrarySource = when (location) {
            is LibraryLocation.Local -> {
                val documents = AndroidLocalDocumentAccess(context)
                val stopping = object : LocalDocumentAccess by documents {
                    override fun rename(treeUri: String, documentId: String, name: String): LocalDocument {
                        if (name == LocalDatabaseCommit.DATABASE) {
                            // Step ③: the original is already renamed away and the new file is complete.
                            ready(requireNotNull(write), documents.openRead(treeUri, documentId).use { sha(it.readBytes()) })
                        }
                        return documents.rename(treeUri, documentId, name)
                    }
                }
                LocalLibrarySource(LocalSourceBackend(stopping, File(context.filesDir, "snapshots/local"), AndroidSnapshotValidator(),
                    Dispatchers.IO)) { dependencies.state.accessKey(it) }
            }
            is LibraryLocation.OneDrive -> {
                val production = dependencies.librarySources.of(BackendKind.ONEDRIVE)
                object : LibrarySource by production {
                    override suspend fun pushDatabase(location: LibraryLocation, staged: File, stagedSha256: String, base: FileVersion,
                        journal: PushJournal): PushOutcome {
                        val outcome = production.pushDatabase(location, staged, stagedSha256, base, journal)
                        if (outcome is PushOutcome.Pushed) ready(requireNotNull(write), outcome.version.token)
                        return outcome
                    }
                }
            }
        }
        isolated(source) { coordinator ->
            write = submit(key, target)
            coordinator.drain()
        }
        fail("The push never reached the point the process is killed at")
    }

    @Test fun aPushStoppedByAProcessKillWasRerunWithoutWritingAgain() = runBlocking<Unit> {
        assumeTrue(arguments.getString("e2eKill") == "verify")
        val record = JSONObject(killRecord.readText())
        val write = TaskId(UUID.fromString(record.getString("task")))
        val location = location()
        // Nothing here drains the queue: the reopened app must have rerun the task by itself.
        val entry = requireNotNull(dependencies.taskQueue.get(write))
        assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
        assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(entry.followUp!!)!!.record.state)
        val imported = dependencies.metadata.currentImport()!!
        assertEquals(record.getBoolean("target"), imported.isRead(imported.metadata.books.single { it.sourceId == record.getLong("book") }))
        assertEquals(record.getString("version"), version(location).token)
        if (location is LibraryLocation.Local) {
            val tree = requireNotNull(dependencies.state.accessKey(location))
            val documents = AndroidLocalDocumentAccess(context)
            for (name in listOf(LocalDatabaseCommit.NEW, LocalDatabaseCommit.OLD))
                assertNull(documents.locate(tree, io.github.chenxiex.calibrecloud.model.RelativeSourcePath(name)))
        }
        // The round's files are gone; the empty task directory is removed with the task by the retention rule.
        assertTrue(File(context.filesDir, "write-staging/${write.value}").listFiles().orEmpty().isEmpty())
    }

    @Test fun twoLibrariesWithTheSameNumericIdsKeepTheirWritesApart() = runBlocking<Unit> {
        assumeTrue(arguments.getString("e2eTwoLibraries") == "true")
        withTimeout(600_000) {
            val libraries = dependencies.state.libraries().map { it.location }
            val local = libraries.single { it is LibraryLocation.Local }
            val oneDrive = libraries.single { it is LibraryLocation.OneDrive }
            val productionWake = dependencies.taskQueue.onWake
            dependencies.taskQueue.onWake = {}
            try {
                awaitBackgroundWork()
                requireNotNull(dependencies.state.switchTo(local))
                val (localBook, localBefore) = book(0)
                val localVersion = version(local)
                val localWrite = submit(localBook, localBefore != true)

                requireNotNull(dependencies.state.switchTo(oneDrive))
                val (oneDriveBook, oneDriveBefore) = book(0)
                assertEquals("Both libraries have a book with this numeric ID", localBook.sourceId, oneDriveBook.sourceId)
                assertNotEquals(localBook.libraryId, oneDriveBook.libraryId)
                assertTrue("The local library's pending book is not shown in this library", pendingAll().isEmpty())
                val oneDriveWrite = submit(oneDriveBook, oneDriveBefore != true)
                dependencies.taskCoordinator.drain()
                assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(oneDriveWrite)!!.record.state)
                val parked = dependencies.taskQueue.get(localWrite)!!.record.state
                assertEquals(TaskState.Waiting(io.github.chenxiex.calibrecloud.tasks.api.FrozenSet(setOf(WaitingReason.INACTIVE_LIBRARY))), parked)
                assertEquals(oneDriveBefore != true, importedRead(oneDriveBook))
                val oneDriveVersion = version(oneDrive)

                requireNotNull(dependencies.state.switchTo(local))
                assertEquals("Only the inactive library's own write may touch it", localVersion, version(local))
                assertEquals(localBefore != true, pending(localBook)?.target)
                dependencies.taskCoordinator.drain()
                assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(localWrite)!!.record.state)
                assertEquals(localBefore != true, importedRead(localBook))
                Log.i(TAG, "two_libraries local=${localWrite.value} onedrive=${oneDriveWrite.value}")

                requireNotNull(dependencies.state.switchTo(oneDrive))
                assertEquals("The local write did not touch the OneDrive library", oneDriveVersion, version(oneDrive))
                assertEquals(oneDriveBefore != true, importedRead(oneDriveBook))
            } finally {
                dependencies.taskQueue.onWake = productionWake
            }
        }
    }

    /** Runs [block] with a coordinator whose sync and write handlers use [source]; the app's own background runs are held. */
    private suspend fun <T> isolated(source: LibrarySource, block: suspend (TaskCoordinator) -> T): T {
        val sources = LibrarySources { if (it == source.backend) source else dependencies.librarySources.of(it) }
        val queue = dependencies.taskQueue
        val coordinator = TaskCoordinator(queue, listOf(
            LibrarySyncTaskHandler(dependencies.state, sources, dependencies.libraryAuthorizations, dependencies.metadata, Dispatchers.IO),
            ReadStatusWriteTaskHandler(dependencies.state, dependencies.metadata, queue, sources, context.filesDir, Dispatchers.IO,
                requestSync = { origin -> dependencies.librarySync.request(origin) })))
        val productionWake = queue.onWake
        queue.onWake = {}
        try {
            awaitBackgroundWork()
            return block(coordinator)
        } finally {
            queue.onWake = productionWake
        }
    }

    private suspend fun location(): LibraryLocation =
        requireNotNull(dependencies.state.current()?.location) { "Add and sync the dedicated test library first" }

    /** The [index]-th book by numeric ID, or the last one in a smaller library, and its imported read state. */
    private suspend fun book(index: Int): Pair<BookKey, Boolean?> {
        val imported = requireNotNull(dependencies.metadata.currentImport())
        assertEquals("Select a valid read column first", ReadColumnStatus.VALID, imported.readColumnStatus)
        val books = imported.metadata.books.sortedBy { it.sourceId }
        val book = books[minOf(index, books.lastIndex)]
        return BookKey(imported.identity.id, book.sourceId, book.sourceUuid) to imported.isRead(book)
    }

    private suspend fun submit(key: BookKey, target: Boolean): TaskId =
        when (val result = dependencies.readStatusService.submit(listOf(key), target, dependencies.state.current()!!.token)) {
            is SubmissionResult.Created -> result.taskId
            is SubmissionResult.Reused -> result.taskId
            else -> error("The mark was not accepted: $result")
        }

    private suspend fun importedRead(key: BookKey): Boolean? {
        val imported = dependencies.metadata.currentImport()!!
        assertEquals(key.libraryId, imported.identity.id)
        return imported.isRead(imported.metadata.books.single { it.sourceId == key.sourceId })
    }

    private suspend fun pendingAll(): Map<BookKey, PendingRead> {
        val imported = dependencies.metadata.currentImport()!!
        return dependencies.taskQueue.pendingReadStatus(imported.identity.id, imported.selectedReadColumn!!)
    }

    private suspend fun pending(key: BookKey): PendingRead? = pendingAll()[key]

    /** The source's current metadata.db version, read through the production backend without importing it. */
    private suspend fun version(location: LibraryLocation): FileVersion = withContext(Dispatchers.IO) {
        val snapshot = requireNotNull(dependencies.librarySources.of(location.backend).acquireSnapshot(location, UUID.randomUUID(), null) {})
        try { snapshot.version } finally { snapshot.file.parentFile!!.deleteRecursively() }
    }

    private suspend fun awaitFinished(id: TaskId) = withTimeout(120_000) {
        while (dependencies.taskQueue.get(id)!!.record.state !is TaskState.Finished) delay(200)
    }

    private suspend fun awaitBackgroundWork() {
        val manager = WorkManager.getInstance(context)
        withTimeout(60_000) {
            while (withContext(Dispatchers.IO) {
                manager.getWorkInfosByTag(BackgroundTasks.QUEUE_TAG).get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
            }) delay(100)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object { const val TAG = "Phase4E2E" }
}
