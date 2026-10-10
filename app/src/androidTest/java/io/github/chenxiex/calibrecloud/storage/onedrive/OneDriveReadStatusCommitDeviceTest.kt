package io.github.chenxiex.calibrecloud.storage.onedrive

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationSession
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.metadata.ReadStatusStaging
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.api.LibrarySources
import io.github.chenxiex.calibrecloud.storage.local.AndroidSnapshotValidator
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.background.BackgroundTasks
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import io.github.chenxiex.calibrecloud.tasks.readstatus.ReadStatusWriteTaskHandler
import io.github.chenxiex.calibrecloud.tasks.sync.LibrarySyncTaskHandler
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes read status to a real OneDrive test library through the production backend. Opt-in with
 * `-e oneDriveWrite true` only after the user has named the dedicated test library, signed in, synced
 * it and selected a valid read column; never run against a real library.
 * [anotherWriterBetweenLookupAndUploadIsRejectedAndTheNextRoundKeepsBothChanges] also uploads another
 * writer's database and needs `-e oneDriveConflict true` as separate consent. Databases are downloaded
 * to the debug app's external files `phase4-step05/` for checks in the container.
 *
 * The conflict runs the production write handler on a backend rebuilt with a test-side interceptor:
 * just before the first upload it lets the other writer upload, so Graph itself answers 412. The
 * interceptor records only method, endpoint class and status, never URLs, paths or tokens.
 */
@RunWith(AndroidJUnit4::class)
class OneDriveReadStatusCommitDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
    private val exports = File(context.getExternalFilesDir(null), "phase4-step05")

    @Before fun requireExplicitOptIn() {
        assumeTrue("Writing a OneDrive source requires a user-named test library and explicit opt-in",
            InstrumentationRegistry.getArguments().getString("oneDriveWrite") == "true")
        assertEquals("Acceptance requires the independent debug application", "io.github.chenxiex.calibrecloud.debug", context.packageName)
        exports.mkdirs()
    }

    @Test fun marksReadThenUnreadThroughTheQueue() = runBlocking<Unit> {
        withTimeout(600_000) {
            val location = location()
            assertNull(dependencies.librarySources.of(BackendKind.ONEDRIVE).writeCapability(location))
            val (key, column) = book(0)
            download("marks-before.db")
            for (target in listOf(true, false)) {
                val write = (dependencies.readStatusService.submit(listOf(key), target, dependencies.state.current()!!.token)
                    as SubmissionResult.Created).taskId
                dependencies.taskCoordinator.drain()
                val entry = dependencies.taskQueue.get(write)!!
                assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
                assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(entry.followUp!!)!!.record.state)
                val imported = dependencies.metadata.currentImport()!!
                assertEquals(target, imported.isRead(imported.metadata.books.single { it.sourceId == key.sourceId }))
                val downloaded = download(if (target) "marks-after-yes.db" else "marks-after-no.db")
                // "No" is written as an explicit 0, never an absent value.
                assertEquals(if (target) 1L else 0L, value(downloaded, column, key.sourceId))
            }
        }
    }

    @Test fun anotherWriterBetweenLookupAndUploadIsRejectedAndTheNextRoundKeepsBothChanges() = runBlocking<Unit> {
        assumeTrue("Uploading another writer's database needs separate consent",
            InstrumentationRegistry.getArguments().getString("oneDriveConflict") == "true")
        withTimeout(600_000) {
            val location = location()
            val (mine, column) = book(1)
            val (theirs, _) = book(2)
            val (base, baseVersion) = snapshot("conflict-base.db")
            val mineTarget = value(base, column, mine.sourceId) != 1L
            val theirsTarget = value(base, column, theirs.sourceId) != 1L
            val other = stage(base, theirs, theirsTarget, "conflict-other.db")

            val calls = Collections.synchronizedList(mutableListOf<String>())
            var otherUploaded = false
            val interceptor = Interceptor { chain ->
                val request = chain.request()
                if (request.method == "PUT" && !otherUploaded) {
                    otherUploaded = true
                    // The other writer, through the production backend, replaces metadata.db after this
                    // round's lookup and before its upload.
                    val result = runBlocking { dependencies.oneDriveBackend.replaceDatabase(location, other, sha(other), baseVersion) }
                    assertTrue("The other writer's upload must succeed", result is OneDriveSourceResult.Available && result.value != null)
                }
                val response: Response = chain.proceed(request)
                calls.add("${request.method}:${endpoint(request.url)}:${response.code}")
                response
            }
            val authorization = dependencies.oneDriveAuthorization
            suspend fun session() = kotlinx.coroutines.currentCoroutineContext()[OneDriveAuthorizationSession]?.id
            val backend = OneDriveSourceBackend({ force -> authorization.backendAccessToken(force, session()) },
                File(context.filesDir, "snapshots/onedrive"), AndroidSnapshotValidator(), Dispatchers.IO,
                client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
                contentClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
                accountProvider = { authorization.accountSubject(session()) })
            val oneDrive = OneDriveLibrarySource(backend)
            val sources = LibrarySources { if (it == BackendKind.ONEDRIVE) oneDrive else dependencies.librarySources.of(it) }
            val queue = dependencies.taskQueue
            val coordinator = TaskCoordinator(queue, listOf(
                LibrarySyncTaskHandler(dependencies.state, sources, dependencies.libraryAuthorizations, dependencies.metadata, Dispatchers.IO),
                ReadStatusWriteTaskHandler(dependencies.state, dependencies.metadata, queue, sources, context.filesDir, Dispatchers.IO,
                    requestSync = { origin -> dependencies.librarySync.request(origin) })))
            val productionWake = queue.onWake
            queue.onWake = {}
            try {
                awaitBackgroundWork()
                val write = (dependencies.readStatusService.submit(listOf(mine), mineTarget, dependencies.state.current()!!.token)
                    as SubmissionResult.Created).taskId
                coordinator.drain()
                val entry = queue.get(write)!!
                assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
                assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(entry.followUp!!)!!.record.state)
                android.util.Log.i("Step05Acceptance", "conflict calls=$calls")
                assertEquals("Graph rejects the stale upload, then the new round's upload succeeds",
                    listOf(412, 200), calls.filter { it.startsWith("PUT:") }.map { it.substringAfterLast(':').toInt() })
            } finally {
                queue.onWake = productionWake
                productionWake()
            }
            val after = download("conflict-after.db")
            assertEquals(if (mineTarget) 1L else 0L, value(after, column, mine.sourceId))
            assertEquals(if (theirsTarget) 1L else 0L, value(after, column, theirs.sourceId))
            val imported = dependencies.metadata.currentImport()!!
            assertEquals(mineTarget, imported.isRead(imported.metadata.books.single { it.sourceId == mine.sourceId }))
            assertEquals(theirsTarget, imported.isRead(imported.metadata.books.single { it.sourceId == theirs.sourceId }))
        }
    }

    private suspend fun location(): LibraryLocation.OneDrive {
        val selected = requireNotNull(dependencies.state.current()) { "Add and sync the dedicated OneDrive test library first" }
        return selected.location as? LibraryLocation.OneDrive ?: error("Select the dedicated OneDrive test library")
    }

    /** The [index]-th book by numeric ID and the read column's table. */
    private suspend fun book(index: Int): Pair<BookKey, Long> {
        val imported = requireNotNull(dependencies.metadata.currentImport())
        assertEquals("Select a valid read column first", ReadColumnStatus.VALID, imported.readColumnStatus)
        val book = imported.metadata.books.sortedBy { it.sourceId }[index]
        return BookKey(imported.identity.id, book.sourceId, book.sourceUuid) to imported.selectedReadColumn!!.sourceId
    }

    private suspend fun stage(base: File, key: BookKey, target: Boolean, name: String): File {
        val preference = requireNotNull(dependencies.metadata.readStatusPreference(key.libraryId))
        val output = File(context.cacheDir, name)
        val result = withContext(Dispatchers.IO) {
            ReadStatusStaging().stage(base, output, preference.libraryUuid, requireNotNull(preference.column), mapOf(key to target))
        }
        return requireNotNull(result.staged).file
    }

    private suspend fun download(name: String): File = snapshot(name).first

    /** Downloads the current metadata.db and its cTag through the production backend into [exports]. */
    private suspend fun snapshot(name: String): Pair<File, FileVersion> = withContext(Dispatchers.IO) {
        val snapshot = requireNotNull(dependencies.librarySources.of(BackendKind.ONEDRIVE)
            .acquireSnapshot(location(), UUID.randomUUID(), null) {})
        val file = File(exports, name)
        try { snapshot.file.copyTo(file, overwrite = true) } finally { snapshot.file.parentFile!!.deleteRecursively() }
        file to snapshot.version
    }

    private suspend fun awaitBackgroundWork() {
        val manager = WorkManager.getInstance(context)
        withTimeout(60_000) {
            while (withContext(Dispatchers.IO) {
                manager.getWorkInfosByTag(BackgroundTasks.QUEUE_TAG).get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
            }) delay(100)
        }
    }

    private fun endpoint(url: okhttp3.HttpUrl) = when {
        url.host != "graph.microsoft.com" -> "download"
        url.pathSegments.any { it.endsWith(":") } -> "items-path"
        url.encodedPath.endsWith("/content") -> "content"
        else -> "other"
    }

    private fun value(database: File, column: Long, book: Long): Long? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM custom_column_$column WHERE book=?", arrayOf(book.toString())).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
