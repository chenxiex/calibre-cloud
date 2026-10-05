package io.github.chenxiex.calibrecloud.tasks

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Seeds the shipped v1 schema directly, so opening the current helper exercises onUpgrade. */
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
                database.readableDatabase.rawQuery("SELECT tree_uri FROM local_authorization", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("content://test.documents/tree/fixture-root", it.getString(0))
                }
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
