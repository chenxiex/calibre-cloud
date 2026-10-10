package io.github.chenxiex.calibrecloud.state

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.api.LocationKeys

/**
 * Migration-test fixture: turns a database created at [ApplicationStateDatabase.VERSION] into the
 * shape an older shipped version left behind, so reopening it runs onUpgrade from that version.
 *
 * This is the only test code that knows the schema history; migration tests call [downgrade] and
 * compare the reopened version with [ApplicationStateDatabase.VERSION]. When that version is raised,
 * add the reversal of the new version to [reversals] (frozen DDL, never the current definitions).
 * Until then every migration test fails here with that instruction, and no other test needs a change.
 */
object StateSchemaHistory {
    /** Version 4 queue, before revocation and library scopes. */
    private const val QUEUE_V4 = """CREATE TABLE queued_tasks (
        task_id TEXT PRIMARY KEY NOT NULL, record TEXT NOT NULL, stage TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0, checkpoint TEXT,
        checkpoint_backend TEXT, checkpoint_version TEXT, recovery_required INTEGER NOT NULL DEFAULT 0, control TEXT)"""
    private const val QUEUE_V8 = """CREATE TABLE queued_tasks (
        task_id TEXT PRIMARY KEY NOT NULL, record TEXT NOT NULL, stage TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0, checkpoint TEXT,
        checkpoint_backend TEXT, checkpoint_version TEXT, recovery_required INTEGER NOT NULL DEFAULT 0, control TEXT,
        revoked INTEGER NOT NULL DEFAULT 0, scope_library_id TEXT)"""
    private const val QUEUE_V13 = """CREATE TABLE queued_tasks (
        task_id TEXT PRIMARY KEY NOT NULL, record TEXT NOT NULL, stage TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0, checkpoint TEXT,
        checkpoint_backend TEXT, checkpoint_version TEXT, recovery_required INTEGER NOT NULL DEFAULT 0, control TEXT,
        revoked INTEGER NOT NULL DEFAULT 0, scope_library_id TEXT, missing_path TEXT)"""
    private const val COPIES_V8 = """CREATE TABLE downloaded_copies (
        library_id TEXT NOT NULL REFERENCES library_bindings(library_id), source_id INTEGER NOT NULL CHECK(source_id > 0),
        source_uuid TEXT NOT NULL, format TEXT NOT NULL, file_generation TEXT NOT NULL, title TEXT NOT NULL,
        size_bytes INTEGER CHECK(size_bytes IS NULL OR size_bytes > 0),
        version_backend TEXT NOT NULL CHECK(version_backend IN ('local', 'onedrive')), version_token TEXT NOT NULL,
        source_availability TEXT NOT NULL CHECK(source_availability IN ('unconfirmed', 'available', 'missing')),
        PRIMARY KEY(library_id, source_id, source_uuid, format), UNIQUE(library_id, file_generation))"""
    private const val IMPORTS_V8 = """CREATE TABLE metadata_imports (
        library_id TEXT PRIMARY KEY NOT NULL REFERENCES library_bindings(library_id),
        import_generation TEXT NOT NULL UNIQUE, imported_at INTEGER NOT NULL, payload TEXT NOT NULL,
        read_column_id INTEGER, read_column_lookup TEXT)"""

    private const val BINDINGS_V9 = """CREATE TABLE library_bindings (
        library_id TEXT PRIMARY KEY NOT NULL, generation TEXT NOT NULL,
        backend TEXT NOT NULL CHECK(backend IN ('local', 'onedrive')), authority TEXT NOT NULL, root_id TEXT NOT NULL,
        account_id TEXT NOT NULL, drive_id TEXT NOT NULL, UNIQUE(backend, authority, root_id, account_id, drive_id, generation))"""
    private const val SELECTION_V9 = """CREATE TABLE current_selection (
        singleton INTEGER PRIMARY KEY CHECK(singleton = 1), token TEXT NOT NULL,
        backend TEXT NOT NULL CHECK(backend IN ('local', 'onedrive')), authority TEXT NOT NULL, root_id TEXT NOT NULL,
        account_id TEXT NOT NULL, drive_id TEXT NOT NULL, library_id TEXT REFERENCES library_bindings(library_id),
        authorization_id TEXT)"""
    private const val COPIES_V9 = """CREATE TABLE downloaded_copies (
        library_id TEXT NOT NULL REFERENCES library_bindings(library_id), source_id INTEGER NOT NULL CHECK(source_id > 0),
        source_uuid TEXT NOT NULL, format TEXT NOT NULL, file_generation TEXT NOT NULL, title TEXT NOT NULL,
        size_bytes INTEGER CHECK(size_bytes IS NULL OR size_bytes > 0),
        version_backend TEXT NOT NULL CHECK(version_backend IN ('local', 'onedrive')), version_token TEXT NOT NULL,
        source_availability TEXT NOT NULL CHECK(source_availability IN ('unconfirmed', 'available', 'missing')),
        calibre_recorded INTEGER NOT NULL DEFAULT 0, calibre_modified TEXT, calibre_size INTEGER,
        PRIMARY KEY(library_id, source_id, source_uuid, format), UNIQUE(library_id, file_generation))"""

    private const val SETTINGS_V10 = """CREATE TABLE application_settings (
        singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
        startup_sync INTEGER NOT NULL DEFAULT 0 CHECK(startup_sync IN (0, 1)))"""

    /** Key: a version; value: turns that version's schema into the previous version's. */
    private val reversals: Map<Int, (SQLiteDatabase) -> Unit> = mapOf(
        14 to { db ->
            // Version 13 kept no change lists, start marks, follow-up syncs or next selection.
            db.execSQL("DROP TABLE read_status_changes")
            db.execSQL("DROP TABLE dispatch_next")
            rebuild(db, "queued_tasks", QUEUE_V13)
        },
        13 to { db ->
            // Version 12 kept the awaited stale-path sync in its own column.
            db.execSQL("ALTER TABLE queued_tasks ADD COLUMN source_sync TEXT")
            db.execSQL("""UPDATE queued_tasks SET source_sync = (SELECT prerequisite_id FROM task_dependencies d
                WHERE d.task_id = queued_tasks.task_id AND d.requirement = 'awaited_sync')""")
            db.execSQL("DELETE FROM task_dependencies WHERE requirement = 'awaited_sync'")
        },
        12 to { db ->
            // Version 11 kept one local grant, that of the selected local directory.
            db.execSQL("CREATE TABLE local_authorization (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), tree_uri TEXT NOT NULL)")
            db.execSQL("""INSERT INTO local_authorization(singleton, tree_uri) SELECT 1, l.access_key FROM configured_libraries l
                JOIN current_selection s ON s.backend = l.backend AND s.location_key = l.location_key
                WHERE l.backend = 'local' AND l.access_key IS NOT NULL""")
            db.execSQL("DROP TABLE configured_libraries")
            db.execSQL("DROP TABLE library_addition")
        },
        11 to { db -> rebuild(db, "application_settings", SETTINGS_V10) },
        10 to { db ->
            locationColumns(db, "library_bindings", BINDINGS_V9)
            db.execSQL("CREATE INDEX binding_location ON library_bindings(backend, authority, root_id, account_id, drive_id)")
            locationColumns(db, "current_selection", SELECTION_V9)
            rebuild(db, "downloaded_copies", COPIES_V9)
        },
        9 to { db ->
            rebuild(db, "downloaded_copies", COPIES_V8)
            rebuild(db, "metadata_imports", IMPORTS_V8)
            rebuild(db, "queued_tasks", QUEUE_V8)
            db.execSQL("DROP TABLE source_throttle")
        },
        8 to { db -> db.execSQL("DROP TABLE last_opened") },
        7 to { db -> db.execSQL("DROP TABLE search_history") },
        6 to { db -> db.execSQL("DROP TABLE application_settings") },
        5 to { db ->
            rebuild(db, "queued_tasks", QUEUE_V4)
            db.execSQL("DROP TABLE cache_cleanup")
            db.execSQL("DROP TABLE library_preferences")
        },
        4 to { db -> db.execSQL("DROP TABLE cover_cache") },
        3 to { db ->
            db.execSQL("DROP TABLE metadata_books")
            db.execSQL("DROP TABLE metadata_imports")
        },
    )

    fun downgrade(db: SQLiteDatabase, version: Int) {
        check(reversals.keys.max() == ApplicationStateDatabase.VERSION) {
            "State schema version ${ApplicationStateDatabase.VERSION} has no reversal in StateSchemaHistory; add it there"
        }
        require(version in reversals.keys.min() - 1 until ApplicationStateDatabase.VERSION)
        db.setForeignKeyConstraintsEnabled(false)
        db.beginTransaction()
        try {
            for (step in ApplicationStateDatabase.VERSION downTo version + 1) reversals.getValue(step)(db)
            db.version = version
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    /** Recreates [table] from frozen [ddl] with the version 9 per-backend location columns instead of location_key. */
    private fun locationColumns(db: SQLiteDatabase, table: String, ddl: String) {
        val rows = db.rawQuery("SELECT * FROM $table", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentValues().apply {
                cursor.columnNames.filter { it != "location_key" }.forEach { put(it, cursor.getString(cursor.getColumnIndexOrThrow(it))) }
                val key = cursor.getString(cursor.getColumnIndexOrThrow("location_key"))
                val location = key?.let { LocationKeys.decode(LocationKeys.backend(getAsString("backend")), it) }
                put("authority", (location as? LibraryLocation.Local)?.authority ?: "")
                put("root_id", when (location) {
                    is LibraryLocation.Local -> location.treeDocumentId
                    is LibraryLocation.OneDrive -> location.rootItemId
                    null -> ""
                })
                put("account_id", (location as? LibraryLocation.OneDrive)?.accountId ?: "")
                put("drive_id", (location as? LibraryLocation.OneDrive)?.driveId ?: "")
            }) }
        }
        db.execSQL("DROP TABLE $table")
        db.execSQL(ddl)
        rows.forEach { db.insertOrThrow(table, null, it) }
    }

    /** Recreates [table] from frozen [ddl], keeping the rows of the columns that DDL still has. */
    private fun rebuild(db: SQLiteDatabase, table: String, ddl: String) {
        db.execSQL("CREATE TEMP TABLE history_backup AS SELECT * FROM $table")
        db.execSQL("DROP TABLE $table")
        db.execSQL(ddl)
        val columns = db.rawQuery("PRAGMA table_info($table)", null).use {
            generateSequence { if (it.moveToNext()) it.getString(1) else null }.toList()
        }.joinToString(",")
        db.execSQL("INSERT INTO $table($columns) SELECT $columns FROM history_backup")
        db.execSQL("DROP TABLE history_backup")
    }
}
