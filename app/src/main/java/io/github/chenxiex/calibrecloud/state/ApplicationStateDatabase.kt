package io.github.chenxiex.calibrecloud.state

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private application state only; never opens, migrates or repairs a Calibre database.
 * Version 1 separates validated bindings, the current candidate selection and complete manifests.
 * Version 2 adds a durable queue without changing version 1 data.
 * Version 3 adds atomic imported metadata and book identity indexes without changing prior data.
 * Future upgrades must migrate in a transaction and preserve manifests, tasks and recovery evidence.
 * Unsupported upgrades fail closed instead of dropping tables; downgrade is also rejected by SQLiteOpenHelper.
 */
class ApplicationStateDatabase(context: Context, name: String = "application-state.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 3) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE library_bindings (
                library_id TEXT PRIMARY KEY NOT NULL,
                generation TEXT NOT NULL,
                backend TEXT NOT NULL CHECK(backend IN ('local', 'onedrive')),
                authority TEXT NOT NULL,
                root_id TEXT NOT NULL,
                account_id TEXT NOT NULL,
                drive_id TEXT NOT NULL,
                UNIQUE(backend, authority, root_id, account_id, drive_id, generation)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE current_selection (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                token TEXT NOT NULL,
                backend TEXT NOT NULL CHECK(backend IN ('local', 'onedrive')),
                authority TEXT NOT NULL,
                root_id TEXT NOT NULL,
                account_id TEXT NOT NULL,
                drive_id TEXT NOT NULL,
                library_id TEXT REFERENCES library_bindings(library_id),
                authorization_id TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE TABLE local_authorization (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), tree_uri TEXT NOT NULL)")
        db.execSQL("""
            CREATE TABLE downloaded_copies (
                library_id TEXT NOT NULL REFERENCES library_bindings(library_id),
                source_id INTEGER NOT NULL CHECK(source_id > 0),
                source_uuid TEXT NOT NULL,
                format TEXT NOT NULL,
                file_generation TEXT NOT NULL,
                title TEXT NOT NULL,
                size_bytes INTEGER CHECK(size_bytes IS NULL OR size_bytes > 0),
                version_backend TEXT NOT NULL CHECK(version_backend IN ('local', 'onedrive')),
                version_token TEXT NOT NULL,
                source_availability TEXT NOT NULL CHECK(source_availability IN ('unconfirmed', 'available', 'missing')),
                PRIMARY KEY(library_id, source_id, source_uuid, format),
                UNIQUE(library_id, file_generation)
            )
        """.trimIndent())
        createQueue(db)
        createMetadata(db)
        // The composite primary key is also the library-scoped ordered manifest index.
        db.execSQL("CREATE INDEX binding_location ON library_bindings(backend, authority, root_id, account_id, drive_id)")
    }

    /** Queue payloads use versioned explicit tags; relational edges enforce referential integrity. */
    private fun createQueue(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue_sequence (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), next_value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO queue_sequence VALUES(1, 0)")
        db.execSQL("""
            CREATE TABLE queued_tasks (
                task_id TEXT PRIMARY KEY NOT NULL,
                record TEXT NOT NULL,
                stage TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                retry_at INTEGER NOT NULL DEFAULT 0,
                checkpoint TEXT,
                checkpoint_backend TEXT,
                checkpoint_version TEXT,
                recovery_required INTEGER NOT NULL DEFAULT 0,
                control TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE task_dependencies (
                task_id TEXT NOT NULL REFERENCES queued_tasks(task_id),
                prerequisite_id TEXT NOT NULL REFERENCES queued_tasks(task_id),
                requirement TEXT NOT NULL,
                PRIMARY KEY(task_id, prerequisite_id, requirement),
                CHECK(task_id != prerequisite_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX prerequisite_tasks ON task_dependencies(prerequisite_id)")
    }

    private fun createMetadata(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE metadata_imports (
                library_id TEXT PRIMARY KEY NOT NULL REFERENCES library_bindings(library_id),
                import_generation TEXT NOT NULL UNIQUE,
                imported_at INTEGER NOT NULL,
                payload TEXT NOT NULL,
                read_column_id INTEGER,
                read_column_lookup TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE metadata_books (
                library_id TEXT NOT NULL REFERENCES library_bindings(library_id),
                source_id INTEGER NOT NULL,
                source_uuid TEXT NOT NULL,
                title TEXT NOT NULL,
                added_at TEXT,
                PRIMARY KEY(library_id, source_id),
                UNIQUE(library_id, source_uuid)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3)
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE current_selection ADD COLUMN authorization_id TEXT")
            createQueue(db)
        }
        createMetadata(db)
    }
}
