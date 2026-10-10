package io.github.chenxiex.calibrecloud.tasks

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.RecoveryCheckpoint
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.StateSchemaHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises shipped v1 and v2 state through onUpgrade, including queue recovery evidence. */
@RunWith(AndroidJUnit4::class)
class TaskSchemaMigrationTest {
    @Test
    fun queueUpgradePreservesValidatedSelectionAndCompleteManifest() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "queue-migration-${UUID.randomUUID()}.db"
        val library = UUID.randomUUID()
        val generation = UUID.randomUUID()
        val token = UUID.randomUUID()
        val bookUuid = UUID.randomUUID()
        val fileGeneration = UUID.randomUUID()
        try {
            VersionOneDatabase(context, name).use { helper ->
                val db = helper.writableDatabase
                db.execSQL("INSERT INTO library_bindings VALUES (?, ?, 'local', 'test.documents', 'fixture-root', '', '')", arrayOf(library.toString(), generation.toString()))
                db.execSQL("INSERT INTO current_selection VALUES (1, ?, 'local', 'test.documents', 'fixture-root', '', '', ?)", arrayOf(token.toString(), library.toString()))
                db.execSQL("INSERT INTO local_authorization VALUES (1, 'content://test.documents/tree/fixture-root')")
                db.execSQL("INSERT INTO downloaded_copies VALUES (?, 1, ?, 'EPUB', ?, 'Fixture title', 23, 'local', 'fixture-version', 'unconfirmed')", arrayOf(library.toString(), bookUuid.toString(), fileGeneration.toString()))
            }
            ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                val expected = LibraryIdentity(LibraryId(library), LibraryLocation.Local("test.documents", "fixture-root"), generation)
                assertEquals(expected, state.binding(expected.id))
                assertEquals(expected, state.current()!!.identity)
                assertEquals(token, state.current()!!.token)
                val copies = state.listCopies(expected.id, 10, 0)
                assertEquals(1, copies.size)
                assertEquals(bookUuid, copies.single().key.book.sourceUuid)
                assertEquals(fileGeneration, copies.single().location.fileGeneration)
                assertEquals("fixture-version", copies.single().savedVersion.token)
                assertEquals(23L, copies.single().sizeBytes)
                assertTrue(database.readableDatabase.version > 1)
                // The single version 1 grant becomes that of the listed current library.
                assertEquals(listOf(io.github.chenxiex.calibrecloud.state.ConfiguredLibrary(expected.location, null,
                    "content://test.documents/tree/fixture-root")), state.libraries())
                database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun metadataUpgradePreservesVersionTwoQueueDependenciesCheckpointAndManifest() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "metadata-migration-${UUID.randomUUID()}.db"
        val identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", "fixture-root"), UUID.randomUUID())
        val bookUuid = UUID.randomUUID()
        val fileGeneration = UUID.randomUUID()
        try {
            val previous = ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                val selected = state.select(identity.location)
                assertTrue(state.bindValidated(selected.token, identity))
                val db = database.writableDatabase
                db.execSQL("INSERT INTO downloaded_copies(library_id,source_id,source_uuid,format,file_generation,title,size_bytes,version_backend,version_token,source_availability) VALUES (?, 1, ?, 'EPUB', ?, 'Fixture title', 23, 'local', 'fixture-version', 'unconfirmed')",
                    arrayOf(identity.id.value.toString(), bookUuid.toString(), fileGeneration.toString()))
                val queue = DurableTaskQueue(database, Dispatchers.IO)
                val sync = (queue.submit(TaskSubmission(TaskRequest.MetadataSync(identity.id), TaskOrigin.STARTUP_SYNC)) as SubmissionResult.Created).taskId
                val book = BookKey(identity.id, 1, bookUuid)
                queue.submit(TaskSubmission(TaskRequest.CoverLoad(book), TaskOrigin.USER_OPEN,
                    dependencies = FrozenSet(listOf(TaskDependency(sync, DependencyRequirement.SUCCESS)))))
                queue.update(sync) { it.copy(checkpoint = RecoveryCheckpoint(UUID.randomUUID(), FileVersion(BackendKind.LOCAL, "fixture-checkpoint"))) }
                val records = queue.list()
                val selection = state.current()
                StateSchemaHistory.downgrade(db, 2)
                selection to records
            }
            ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                val queue = DurableTaskQueue(database, Dispatchers.IO)
                assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
                assertEquals(previous.first, state.current())
                assertEquals(identity, state.binding(identity.id))
                assertEquals(previous.second, queue.list())
                val copy = state.listCopies(identity.id, 10, 0).single()
                assertEquals(bookUuid, copy.key.book.sourceUuid)
                assertEquals(fileGeneration, copy.location.fileGeneration)
                assertEquals("fixture-version", copy.savedVersion.token)
                assertEquals(23L, copy.sizeBytes)
                listOf("metadata_imports", "metadata_books").forEach { table ->
                    database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
                }
                val lastSequence = previous.second.maxOf { it.record.scheduling.sequence.value }
                val later = queue.submit(TaskSubmission(TaskRequest.CoverLoad(BookKey(identity.id, 2, UUID.randomUUID())), TaskOrigin.USER_OPEN)) as SubmissionResult.Created
                assertTrue(queue.get(later.taskId)!!.record.scheduling.sequence.value > lastSequence)
                database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionEightUpgradeAddsRequestCostStateWithoutTouchingExistingRows() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "request-cost-migration-${UUID.randomUUID()}.db"
        val identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.OneDrive("account", "drive", "root"), UUID.randomUUID())
        try {
            val previous = ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                assertTrue(state.bindValidated(state.select(identity.location).token, identity))
                val db = database.writableDatabase
                db.execSQL("INSERT INTO downloaded_copies(library_id,source_id,source_uuid,format,file_generation,title,size_bytes,version_backend,version_token,source_availability) VALUES (?, 1, ?, 'EPUB', ?, 'Fixture title', 23, 'onedrive', 'fixture-version', 'available')",
                    arrayOf(identity.id.value.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                db.execSQL("INSERT INTO metadata_imports(library_id,import_generation,imported_at,payload) VALUES (?, ?, 7, '{}')",
                    arrayOf(identity.id.value.toString(), UUID.randomUUID().toString()))
                val queue = DurableTaskQueue(database, Dispatchers.IO)
                queue.submit(TaskSubmission(TaskRequest.CoverLoad(BookKey(identity.id, 1, UUID.randomUUID())), TaskOrigin.VISIBLE_COVER))
                val copies = state.listCopies(identity.id, 10, 0)
                val records = queue.list()
                StateSchemaHistory.downgrade(db, 8)
                copies to records
            }
            ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
                assertEquals(previous.first, state.listCopies(identity.id, 10, 0))
                assertEquals(previous.second, DurableTaskQueue(database, Dispatchers.IO).list())
                database.readableDatabase.rawQuery("SELECT calibre_recorded, calibre_modified, calibre_size FROM downloaded_copies", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                    assertTrue(it.isNull(1) && it.isNull(2))
                }
                database.readableDatabase.rawQuery("SELECT imported_at, source_version FROM metadata_imports", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(7L, it.getLong(0))
                    assertTrue(it.isNull(1))
                }
                database.readableDatabase.rawQuery("SELECT COUNT(*) FROM source_throttle", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionNineUpgradeStoresLocationsAsOpaqueKeysWithoutTouchingRows() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "location-key-migration-${UUID.randomUUID()}.db"
        val remote = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.OneDrive("account", "drive", "root"), UUID.randomUUID())
        val local = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", "fixture-root"), UUID.randomUUID())
        try {
            val previous = ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                assertTrue(state.bindValidated(state.select(remote.location).token, remote))
                assertTrue(state.bindValidated(state.select(local.location).token, local))
                database.writableDatabase.execSQL("INSERT INTO downloaded_copies(library_id,source_id,source_uuid,format,file_generation,title,size_bytes,version_backend,version_token,source_availability,calibre_recorded,calibre_size) VALUES (?, 1, ?, 'EPUB', ?, 'Fixture title', 23, 'onedrive', 'fixture-version', 'available', 1, 23)",
                    arrayOf(remote.id.value.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                val selection = state.current()
                val copies = state.listCopies(remote.id, 10, 0)
                StateSchemaHistory.downgrade(database.writableDatabase, 9)
                selection to copies
            }
            ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
                assertEquals(previous.first, state.current())
                assertEquals(listOf(remote), state.bindingsAt(remote.location))
                assertEquals(listOf(local), state.bindingsAt(local.location))
                assertEquals(previous.second, state.listCopies(remote.id, 10, 0))
                database.readableDatabase.rawQuery("SELECT calibre_recorded, calibre_size FROM downloaded_copies", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                    assertEquals(23L, it.getLong(1))
                }
                database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionTwelveUpgradeMovesTheAwaitedSyncIntoADependencyEdge() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "awaited-sync-migration-${UUID.randomUUID()}.db"
        val identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.OneDrive("account", "drive", "root"), UUID.randomUUID())
        try {
            val previous = ApplicationStateDatabase(context, name).use { database ->
                val state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
                assertTrue(state.bindValidated(state.select(identity.location).token, identity))
                val queue = DurableTaskQueue(database, Dispatchers.IO)
                val sync = (queue.submit(TaskSubmission(TaskRequest.MetadataSync(identity.id), TaskOrigin.USER_DOWNLOAD)) as SubmissionResult.Created).taskId
                val cover = (queue.submit(TaskSubmission(TaskRequest.CoverLoad(BookKey(identity.id, 1, UUID.randomUUID())),
                    TaskOrigin.USER_OPEN)) as SubmissionResult.Created).taskId
                queue.update(cover) { it.copy(sourceSync = sync, missingPath = io.github.chenxiex.calibrecloud.model.RelativeSourcePath("a/cover.jpg")) }
                val records = queue.list()
                StateSchemaHistory.downgrade(database.writableDatabase, 12)
                database.readableDatabase.rawQuery("SELECT source_sync FROM queued_tasks WHERE task_id = ?", arrayOf(cover.value.toString())).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(sync.value.toString(), it.getString(0))
                }
                Triple(records, cover, sync)
            }
            ApplicationStateDatabase(context, name).use { database ->
                val queue = DurableTaskQueue(database, Dispatchers.IO)
                assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
                assertEquals(previous.first, queue.list())
                assertEquals(previous.third, queue.get(previous.second)!!.sourceSync)
                database.readableDatabase.rawQuery("SELECT COUNT(*) FROM queued_tasks WHERE source_sync IS NOT NULL", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                // The cover waits on the unfinished sync, which therefore runs first.
                assertEquals(previous.third, queue.claim(0, { emptySet() }, { true })!!.record.id)
                assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.DEPENDENCY))), queue.get(previous.second)!!.record.state)
                database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private class VersionOneDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE library_bindings (library_id TEXT PRIMARY KEY NOT NULL, generation TEXT NOT NULL, backend TEXT NOT NULL CHECK(backend IN ('local', 'onedrive')), authority TEXT NOT NULL, root_id TEXT NOT NULL, account_id TEXT NOT NULL, drive_id TEXT NOT NULL, UNIQUE(backend, authority, root_id, account_id, drive_id, generation))")
            db.execSQL("CREATE TABLE current_selection (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), token TEXT NOT NULL, backend TEXT NOT NULL CHECK(backend IN ('local', 'onedrive')), authority TEXT NOT NULL, root_id TEXT NOT NULL, account_id TEXT NOT NULL, drive_id TEXT NOT NULL, library_id TEXT REFERENCES library_bindings(library_id))")
            db.execSQL("CREATE TABLE local_authorization (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), tree_uri TEXT NOT NULL)")
            db.execSQL("CREATE TABLE downloaded_copies (library_id TEXT NOT NULL REFERENCES library_bindings(library_id), source_id INTEGER NOT NULL CHECK(source_id > 0), source_uuid TEXT NOT NULL, format TEXT NOT NULL, file_generation TEXT NOT NULL, title TEXT NOT NULL, size_bytes INTEGER CHECK(size_bytes IS NULL OR size_bytes > 0), version_backend TEXT NOT NULL CHECK(version_backend IN ('local', 'onedrive')), version_token TEXT NOT NULL, source_availability TEXT NOT NULL CHECK(source_availability IN ('unconfirmed', 'available', 'missing')), PRIMARY KEY(library_id, source_id, source_uuid, format), UNIQUE(library_id, file_generation))")
            db.execSQL("CREATE INDEX binding_location ON library_bindings(backend, authority, root_id, account_id, drive_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Fixture has no upgrade")
    }
}
