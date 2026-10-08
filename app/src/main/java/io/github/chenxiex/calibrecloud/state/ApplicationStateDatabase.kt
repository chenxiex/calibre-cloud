package io.github.chenxiex.calibrecloud.state

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.api.LocationKeys
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private application state only; never opens, migrates or repairs a Calibre database.
 * Version 1 separates validated bindings, the current candidate selection and complete manifests.
 * Version 2 adds a durable queue without changing version 1 data.
 * Version 3 adds atomic imported metadata and book identity indexes without changing prior data.
 * Version 4 adds independent complete cover cache records.
 * Version 5 adds irreversible producer revocation, recoverable cleanup and retained preferences.
 * Version 6 adds the default-off process startup sync setting.
 * Version 7 adds per-library search history.
 * Version 8 adds the per-library last opened book.
 * Version 9 adds OneDrive request-cost state: the imported source version, the Calibre record a
 * downloaded copy was confirmed against, a task's one stale-path sync with its missing path, and
 * library throttle deadlines.
 * Version 10 stores library locations as opaque storage keys instead of per-backend columns.
 * Future upgrades must migrate in a transaction and preserve manifests, tasks and recovery evidence.
 * Unsupported upgrades fail closed instead of dropping tables; downgrade is also rejected by SQLiteOpenHelper.
 * Raising [VERSION] also requires its reversal in the androidTest StateSchemaHistory fixture.
 */
class ApplicationStateDatabase(context: Context, name: String = "application-state.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {
    companion object {
        const val VERSION = 10
    }

    private val privateFiles = context.applicationContext.filesDir

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBindings(db)
        createSelection(db)
        db.execSQL("CREATE TABLE local_authorization (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), tree_uri TEXT NOT NULL)")
        createCopies(db, "downloaded_copies")
        createQueue(db)
        createMetadata(db)
        createCovers(db)
        createMaintenance(db)
        createStartupSetting(db)
        createSearchHistory(db)
        createLastOpened(db)
        createRequestCosts(db)
    }

    /**
     * Locations are stored as the backend code and the storage layer's opaque [LocationKeys] key; backend
     * codes are not constrained here, so adding a backend changes no table.
     */
    private fun createBindings(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE library_bindings (
                library_id TEXT PRIMARY KEY NOT NULL,
                generation TEXT NOT NULL,
                backend TEXT NOT NULL,
                location_key TEXT NOT NULL,
                UNIQUE(backend, location_key, generation)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX binding_location ON library_bindings(backend, location_key)")
    }

    /** A null location_key is a candidate whose stable root is not chosen yet. */
    private fun createSelection(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE current_selection (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                token TEXT NOT NULL,
                backend TEXT NOT NULL,
                location_key TEXT,
                library_id TEXT REFERENCES library_bindings(library_id),
                authorization_id TEXT
            )
        """.trimIndent())
    }

    /** The composite primary key is also the library-scoped ordered manifest index. */
    private fun createCopies(db: SQLiteDatabase, table: String) {
        db.execSQL("""
            CREATE TABLE $table (
                library_id TEXT NOT NULL REFERENCES library_bindings(library_id),
                source_id INTEGER NOT NULL CHECK(source_id > 0),
                source_uuid TEXT NOT NULL,
                format TEXT NOT NULL,
                file_generation TEXT NOT NULL,
                title TEXT NOT NULL,
                size_bytes INTEGER CHECK(size_bytes IS NULL OR size_bytes > 0),
                version_backend TEXT NOT NULL,
                version_token TEXT NOT NULL,
                source_availability TEXT NOT NULL CHECK(source_availability IN ('unconfirmed', 'available', 'missing')),
                calibre_recorded INTEGER NOT NULL DEFAULT 0,
                calibre_modified TEXT,
                calibre_size INTEGER,
                PRIMARY KEY(library_id, source_id, source_uuid, format),
                UNIQUE(library_id, file_generation)
            )
        """.trimIndent())
    }

    /**
     * Version 10: replaces the per-backend location columns of bindings and the selection with opaque
     * keys and drops the backend CHECK constraints. Parent rows are rewritten under deferred foreign keys;
     * commit fails if any reference is left without its binding.
     */
    private fun migrateLocationKeys(db: SQLiteDatabase) {
        db.execSQL("PRAGMA defer_foreign_keys = ON")
        fun Cursor.legacyKey(): String {
            fun text(name: String) = getString(getColumnIndexOrThrow(name))
            return LocationKeys.encode(when (text("backend")) {
                "local" -> LibraryLocation.Local(text("authority"), text("root_id"))
                else -> LibraryLocation.OneDrive(text("account_id"), text("drive_id"), text("root_id"))
            })
        }
        val selection = db.rawQuery("SELECT * FROM current_selection", null).use {
            if (!it.moveToFirst()) null else ContentValues().apply {
                listOf("singleton", "token", "backend", "library_id", "authorization_id").forEach { column ->
                    put(column, it.getString(it.getColumnIndexOrThrow(column)))
                }
                if (it.getString(it.getColumnIndexOrThrow("root_id")).isEmpty()) putNull("location_key") else put("location_key", it.legacyKey())
            }
        }
        val bindings = db.rawQuery("SELECT * FROM library_bindings", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentValues().apply {
                listOf("library_id", "generation", "backend").forEach { put(it, cursor.getString(cursor.getColumnIndexOrThrow(it))) }
                put("location_key", cursor.legacyKey())
            }) }
        }
        db.execSQL("DROP TABLE current_selection")
        db.execSQL("DROP TABLE library_bindings")
        createBindings(db)
        bindings.forEach { db.insertOrThrow("library_bindings", null, it) }
        createSelection(db)
        if (selection != null) db.insertOrThrow("current_selection", null, selection)
        createCopies(db, "downloaded_copies_v10")
        val columns = "library_id,source_id,source_uuid,format,file_generation,title,size_bytes,version_backend,version_token," +
            "source_availability,calibre_recorded,calibre_modified,calibre_size"
        db.execSQL("INSERT INTO downloaded_copies_v10($columns) SELECT $columns FROM downloaded_copies")
        db.execSQL("DROP TABLE downloaded_copies")
        db.execSQL("ALTER TABLE downloaded_copies_v10 RENAME TO downloaded_copies")
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

    private fun createCovers(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE cover_cache (
                library_id TEXT NOT NULL REFERENCES library_bindings(library_id),
                source_id INTEGER NOT NULL,
                source_uuid TEXT NOT NULL,
                file_generation TEXT NOT NULL UNIQUE,
                size_bytes INTEGER NOT NULL CHECK(size_bytes > 0),
                last_access INTEGER NOT NULL,
                PRIMARY KEY(library_id, source_id, source_uuid)
            )
        """.trimIndent())
    }

    /** Cleanup journals contain only generated private paths and frozen identity scopes. */
    private fun createMaintenance(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE queued_tasks ADD COLUMN revoked INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE queued_tasks ADD COLUMN scope_library_id TEXT")
        db.execSQL("CREATE TABLE cache_cleanup (cleanup_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE library_preferences (
            library_id TEXT PRIMARY KEY NOT NULL REFERENCES library_bindings(library_id),
            source_uuid TEXT, read_column_id INTEGER, read_column_lookup TEXT,
            last_imported_at INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""INSERT INTO library_preferences(library_id,read_column_id,read_column_lookup,last_imported_at)
            SELECT library_id,read_column_id,read_column_lookup,imported_at FROM metadata_imports""")
        db.rawQuery("SELECT library_id,payload FROM metadata_imports", null).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = org.json.JSONObject(cursor.getString(1))
                if (!payload.isNull("libraryUuid")) db.execSQL("UPDATE library_preferences SET source_uuid = ? WHERE library_id = ?",
                    arrayOf(payload.getString("libraryUuid"), cursor.getString(0)))
            }
        }
    }

    /** Legacy candidate inputs are matched only by location or unambiguous private snapshot evidence. */
    private fun migrateCandidateScopes(db: SQLiteDatabase) {
        db.rawQuery("SELECT task_id,record FROM queued_tasks", null).use { tasks ->
            while (tasks.moveToNext()) {
                val request = io.github.chenxiex.calibrecloud.tasks.persistence.TaskCodec.decode(tasks.getString(1)).submission.request
                    as? io.github.chenxiex.calibrecloud.tasks.api.TaskRequest.CandidateConfiguration ?: continue
                val backend = if (request.context.backend == io.github.chenxiex.calibrecloud.model.BackendKind.LOCAL) "local" else "onedrive"
                val matches = mutableSetOf<String>()
                db.rawQuery("SELECT library_id FROM current_selection WHERE token = ? AND library_id IS NOT NULL",
                    arrayOf(request.context.selectionToken.toString())).use { if (it.moveToFirst()) matches.add(it.getString(0)) }
                if (matches.isEmpty() && backend == "onedrive" && request.directoryItemId != null) {
                    db.rawQuery("SELECT library_id FROM library_bindings WHERE backend = ? AND root_id = ?",
                        arrayOf(backend, request.directoryItemId)).use { while (it.moveToNext()) matches.add(it.getString(0)) }
                }
                if (matches.isEmpty()) {
                    val directory = java.io.File(privateFiles, "snapshots/$backend/${tasks.getString(0)}")
                    if (!java.nio.file.Files.isSymbolicLink(directory.toPath())) directory.listFiles().orEmpty().filter {
                        it.extension == "db" && !java.nio.file.Files.isSymbolicLink(it.toPath())
                    }.forEach { snapshot ->
                        runCatching {
                            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
                                source.rawQuery("SELECT uuid FROM library_id", null).use { ids ->
                                    if (ids.moveToFirst()) db.rawQuery("""SELECT b.library_id FROM library_bindings b
                                        JOIN library_preferences p ON b.library_id=p.library_id WHERE b.backend=? AND p.source_uuid=?""",
                                        arrayOf(backend, ids.getString(0))).use { while (it.moveToNext()) matches.add(it.getString(0)) }
                                }
                            }
                        }
                    }
                }
                if (matches.size == 1) db.execSQL("UPDATE queued_tasks SET scope_library_id = ? WHERE task_id = ?",
                    arrayOf(matches.single(), tasks.getString(0)))
            }
        }
    }

    private fun createStartupSetting(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE application_settings (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
            startup_sync INTEGER NOT NULL DEFAULT 0 CHECK(startup_sync IN (0, 1))
        )""".trimIndent())
        db.execSQL("INSERT INTO application_settings(singleton, startup_sync) VALUES (1, 0)")
    }

    /** Executed queries only; [sequence] orders them newest first within one library. */
    private fun createSearchHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE search_history (
            library_id TEXT NOT NULL REFERENCES library_bindings(library_id),
            query TEXT NOT NULL CHECK(length(query) > 0),
            sequence INTEGER NOT NULL,
            PRIMARY KEY(library_id, query)
        )""".trimIndent())
    }

    /** One minimal record per library: the book and format last handed to a reader. */
    private fun createLastOpened(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE last_opened (
            library_id TEXT PRIMARY KEY NOT NULL REFERENCES library_bindings(library_id),
            source_id INTEGER NOT NULL CHECK(source_id > 0),
            source_uuid TEXT NOT NULL,
            format TEXT NOT NULL,
            title TEXT NOT NULL
        )""".trimIndent())
    }

    /**
     * calibre_modified/calibre_size hold books.last_modified and the format size of the import a copy
     * was published or confirmed against; calibre_recorded distinguishes unknown sizes from copies
     * downloaded before version 9. Columns are added only when absent.
     */
    private fun createRequestCosts(db: SQLiteDatabase) {
        fun add(table: String, column: String, definition: String) {
            val present = db.rawQuery("PRAGMA table_info($table)", null).use {
                generateSequence { if (it.moveToNext()) it.getString(1) else null }.any { name -> name == column }
            }
            if (!present) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
        add("metadata_imports", "source_version", "TEXT")
        add("downloaded_copies", "calibre_recorded", "INTEGER NOT NULL DEFAULT 0")
        add("downloaded_copies", "calibre_modified", "TEXT")
        add("downloaded_copies", "calibre_size", "INTEGER")
        add("queued_tasks", "source_sync", "TEXT")
        add("queued_tasks", "missing_path", "TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS source_throttle (scope TEXT PRIMARY KEY NOT NULL, until INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1 until VERSION && newVersion == VERSION)
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE current_selection ADD COLUMN authorization_id TEXT")
            createQueue(db)
        }
        if (oldVersion <= 2) createMetadata(db)
        if (oldVersion <= 3) createCovers(db)
        if (oldVersion <= 4) {
            createMaintenance(db)
            migrateCandidateScopes(db)
        }
        if (oldVersion <= 5) createStartupSetting(db)
        if (oldVersion <= 6) createSearchHistory(db)
        if (oldVersion <= 7) createLastOpened(db)
        createRequestCosts(db)
        if (oldVersion <= 9) migrateLocationKeys(db)
    }
}
