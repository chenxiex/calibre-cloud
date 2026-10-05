package io.github.chenxiex.calibrecloud.state

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private application state only; never opens, migrates or repairs a Calibre database.
 * Version 1 separates validated bindings, the current candidate selection and complete manifests.
 * Future upgrades must migrate in a transaction and preserve manifests, tasks and recovery evidence.
 * Unsupported upgrades fail closed instead of dropping tables; downgrade is also rejected by SQLiteOpenHelper.
 */
class ApplicationStateDatabase(context: Context, name: String = "application-state.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
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
                library_id TEXT REFERENCES library_bindings(library_id)
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
        // The composite primary key is also the library-scoped ordered manifest index.
        db.execSQL("CREATE INDEX binding_location ON library_bindings(backend, authority, root_id, account_id, drive_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("An explicit non-destructive application-state migration is required")
    }
}
