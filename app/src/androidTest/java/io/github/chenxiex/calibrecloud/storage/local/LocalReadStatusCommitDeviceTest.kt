package io.github.chenxiex.calibrecloud.storage.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.metadata.ReadStatusStaging
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.PushJournal
import io.github.chenxiex.calibrecloud.storage.api.PushOutcome
import io.github.chenxiex.calibrecloud.storage.local.LocalDatabaseCommit.Companion.DATABASE
import io.github.chenxiex.calibrecloud.storage.local.LocalDatabaseCommit.Companion.NEW
import io.github.chenxiex.calibrecloud.storage.local.LocalDatabaseCommit.Companion.OLD
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes read status to a real local test copy through the production SAF access. Opt-in with
 * `-e localWrite true` only after the user has named the dedicated test copy, granted it read/write
 * through the system picker, synced it and selected a valid read column; never run against a real
 * library. [anotherWriterBeforeTheRenameIsAConflict] also replaces the source database as another
 * writer would and needs `-e localConflict true` as separate consent. Before/after databases are
 * copied to the debug app's external files `phase4-step04/` for checks in the container.
 *
 * The interruption between the renames is a test-side injected fault (an Error no code catches),
 * not a real process death.
 */
@RunWith(AndroidJUnit4::class)
class LocalReadStatusCommitDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
    private val documents = AndroidLocalDocumentAccess(context)
    private val exports = File(context.getExternalFilesDir(null), "phase4-step04")

    private class Crash : Error()

    private class Journal : PushJournal {
        var value: String? = null
        var onWrite: (String?) -> Unit = {}
        override suspend fun read() = value
        override suspend fun write(value: String?) { this.value = value; onWrite(value) }
    }

    @Before fun requireExplicitOptIn() {
        assumeTrue("Writing a local source requires a user-named test copy and explicit opt-in",
            InstrumentationRegistry.getArguments().getString("localWrite") == "true")
        exports.mkdirs()
    }

    @Test fun marksReadThenUnreadThroughTheQueue() = runBlocking<Unit> {
        val tree = tree()
        assertNull(dependencies.librarySources.of(BackendKind.LOCAL).writeCapability(dependencies.state.current()!!.location!!))
        val (key, column) = book(0)
        export("marks-before.db")
        for (target in listOf(true, false)) {
            val submitted = dependencies.readStatusService.submit(listOf(key), target, dependencies.state.current()!!.token)
            val write = (submitted as SubmissionResult.Created).taskId
            dependencies.taskCoordinator.drain()
            val entry = dependencies.taskQueue.get(write)!!
            assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
            assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(entry.followUp!!)!!.record.state)
            val imported = dependencies.metadata.currentImport()!!
            assertEquals(target, imported.isRead(imported.metadata.books.single { it.sourceId == key.sourceId }))
            assertNoTemporaryFiles(tree)
            val exported = export(if (target) "marks-after-yes.db" else "marks-after-no.db")
            // "No" is written as an explicit 0, never an absent value.
            assertEquals(if (target) 1L else 0L, value(exported, column, key.sourceId))
        }
    }

    @Test fun aPushInterruptedBetweenTheRenamesIsFinishedByTheNextRound() = runBlocking<Unit> {
        val tree = tree()
        val (key, column) = book(1)
        val base = export("interrupted-base.db")
        val target = value(base, column, key.sourceId) != 1L
        val staged = stage(base, column, key, target)
        val stagedSha = sha(staged.readBytes())
        val interrupted = object : LocalDocumentAccess by documents {
            override fun rename(treeUri: String, documentId: String, name: String): LocalDocument {
                if (name == DATABASE) throw Crash()
                return documents.rename(treeUri, documentId, name)
            }
        }
        val journal = Journal()
        try {
            LocalDatabaseCommit(interrupted, Dispatchers.IO).push(tree, staged, stagedSha, versionOf(base), journal)
            fail("The push must stop between the renames")
        } catch (_: Crash) {}
        assertNull(locate(tree, DATABASE))
        assertEquals(sha(base.readBytes()), hash(tree, OLD))
        assertEquals(stagedSha, hash(tree, NEW))

        LocalDatabaseCommit(documents, Dispatchers.IO).finish(tree, journal)
        assertNull(journal.value)
        assertEquals(stagedSha, hash(tree, DATABASE))
        assertNoTemporaryFiles(tree)
        sync()
        val imported = dependencies.metadata.currentImport()!!
        assertEquals(target, imported.isRead(imported.metadata.books.single { it.sourceId == key.sourceId }))
        export("interrupted-after.db")
    }

    @Test fun anotherWriterBeforeTheRenameIsAConflict() = runBlocking<Unit> {
        assumeTrue("Replacing the source database needs separate consent",
            InstrumentationRegistry.getArguments().getString("localConflict") == "true")
        val tree = tree()
        val (mine, column) = book(2)
        val (theirs, _) = book(3)
        val base = export("conflict-base.db")
        val mineTarget = value(base, column, mine.sourceId) != 1L
        val theirsTarget = value(base, column, theirs.sourceId) != 1L
        val staged = stage(base, column, mine, mineTarget)
        val other = stage(base, column, theirs, theirsTarget, "conflict-other.db")
        val journal = Journal()
        // The other writer replaces metadata.db after this push checked its names but before the rename.
        journal.onWrite = { value ->
            if (value?.contains("NEW_WRITTEN") == true) {
                val database = requireNotNull(locate(tree, DATABASE))
                documents.openWrite(tree, database.id).use { output -> other.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            }
        }
        val outcome = LocalDatabaseCommit(documents, Dispatchers.IO).push(tree, staged, sha(staged.readBytes()), versionOf(base), journal)
        assertEquals(PushOutcome.Conflict, outcome)
        assertEquals(sha(other.readBytes()), hash(tree, DATABASE))
        assertNoTemporaryFiles(tree)

        // Through the queue the write fetches the other writer's database and keeps its change.
        sync()
        val write = (dependencies.readStatusService.submit(listOf(mine), mineTarget, dependencies.state.current()!!.token)
            as SubmissionResult.Created).taskId
        dependencies.taskCoordinator.drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(write)!!.record.state)
        val after = export("conflict-after.db")
        assertEquals(if (mineTarget) 1L else 0L, value(after, column, mine.sourceId))
        assertEquals(if (theirsTarget) 1L else 0L, value(after, column, theirs.sourceId))
        assertNoTemporaryFiles(tree)
    }

    private suspend fun tree(): String {
        val selected = requireNotNull(dependencies.state.current()) { "Add and sync the dedicated local test copy first" }
        assertEquals(BackendKind.LOCAL, selected.backend)
        val tree = requireNotNull(selected.location?.let { dependencies.state.accessKey(it) })
        assertTrue("The test copy must be granted with write access", documents.writeGranted(tree))
        assertNoTemporaryFiles(tree)
        return tree
    }

    /** The [index]-th book by numeric ID and the read column's table. */
    private suspend fun book(index: Int): Pair<BookKey, Long> {
        val imported = requireNotNull(dependencies.metadata.currentImport())
        assertEquals("Select a valid read column first", ReadColumnStatus.VALID, imported.readColumnStatus)
        val book = imported.metadata.books.sortedBy { it.sourceId }[index]
        return BookKey(imported.identity.id, book.sourceId, book.sourceUuid) to imported.selectedReadColumn!!.sourceId
    }

    private suspend fun stage(base: File, column: Long, key: BookKey, target: Boolean, name: String = "staged-${UUID.randomUUID()}.db"): File {
        val preference = requireNotNull(dependencies.metadata.readStatusPreference(key.libraryId))
        val selected = requireNotNull(preference.column)
        assertEquals(column, selected.sourceId)
        val output = File(context.cacheDir, name)
        val result = withContext(Dispatchers.IO) {
            ReadStatusStaging().stage(base, output, preference.libraryUuid, selected, mapOf(key to target))
        }
        return requireNotNull(result.staged).file
    }

    private suspend fun sync() {
        val sync = requireNotNull(dependencies.librarySync.request(TaskOrigin.MANUAL_SYNC))
        dependencies.taskCoordinator.drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(sync)!!.record.state)
    }

    private suspend fun export(name: String): File = withContext(Dispatchers.IO) {
        val tree = requireNotNull(dependencies.state.current()?.location?.let { dependencies.state.accessKey(it) })
        val file = File(exports, name)
        documents.openRead(tree, requireNotNull(locate(tree, DATABASE)).id).use { input -> file.outputStream().use { input.copyTo(it) } }
        file
    }

    private fun value(database: File, column: Long, book: Long): Long? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM custom_column_$column WHERE book=?", arrayOf(book.toString())).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        }

    private fun locate(tree: String, name: String) = documents.locate(tree, RelativeSourcePath(name))
    private fun hash(tree: String, name: String) = documents.openRead(tree, requireNotNull(locate(tree, name)).id).use { sha(it.readBytes()) }
    private fun assertNoTemporaryFiles(tree: String) {
        assertNull(locate(tree, NEW))
        assertNull(locate(tree, OLD))
    }
    private fun versionOf(file: File) = io.github.chenxiex.calibrecloud.model.FileVersion(BackendKind.LOCAL, sha(file.readBytes()))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
