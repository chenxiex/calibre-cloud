package io.github.chenxiex.calibrecloud.metadata

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class ReadColumnStatus { NOT_CONFIGURED, VALID, INVALID }

data class ImportedLibrary(
    val identity: LibraryIdentity,
    val generation: UUID,
    val importedAt: Long,
    val metadata: ParsedLibrary,
    val selectedReadColumn: CustomColumnId?,
    val readColumnStatus: ReadColumnStatus,
) {
    /** Invalid configuration has no read state; only imported source boolean values supply it. */
    fun isRead(book: ImportedBook): Boolean? = if (readColumnStatus != ReadColumnStatus.VALID) null
        else (book.customValues[selectedReadColumn!!.sourceId] as? ImportedColumnValue.Bool)?.value == true
}

/**
 * Accepts only private backend snapshots. Builds an immutable snapshot and complete index before
 * one app-state transaction publishes binding, books and column definitions. Readers never access
 * source storage. An interrupted attempt has no database pointer and is collected on the next import.
 * Library UUID evidence is preferred; a conflicting UUID for a surviving numeric book ID also
 * isolates an incompatible replacement. A content hash is never treated as library identity.
 */
class MetadataRepository(
    private val database: ApplicationStateDatabase,
    private val state: ApplicationStateRepository,
    private val root: File,
    private val io: CoroutineDispatcher,
) {
    private val publication = Mutex()

    suspend fun importSnapshot(selectionToken: UUID, file: File, taskId: UUID? = null, check: suspend () -> Unit = {}): LibraryIdentity? =
        withContext(io) { publication.withLock {
            check()
            val selected = state.current() ?: return@withLock null
            if (selected.token != selectionToken || selected.location == null) return@withLock null
            val location = selected.location
            val parsed = CalibreSnapshotParser().parse(file)
            check()
            require(root.mkdirs() || root.isDirectory)
            collectUnpublished()
            val generation = UUID.randomUUID()
            val directory = File(root, generation.toString())
            kotlin.check(directory.mkdir())
            var published = false
            try {
                val snapshot = File(directory, "metadata.db")
                file.inputStream().use { source -> FileOutputStream(snapshot).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                } }
                val payload = parsed.toJson()
                check()
                val db = database.writableDatabase
                db.beginTransaction()
                val committed = try {
                    if (taskId != null && db.rawQuery("SELECT control FROM queued_tasks WHERE task_id = ? AND revoked = 0",
                        arrayOf(taskId.toString())).use { it.moveToFirst() && it.getString(0) in setOf("pause", "cancel") }) return@withLock null
                    if (taskId != null && DurableTaskQueue.isRevoked(db, TaskId(taskId))) return@withLock null
                    val current = state.current(db)
                    if (current?.token != selectionToken || current.location != location) return@withLock null
                    val identity = compatibleBinding(db, location, parsed) ?: LibraryIdentity(
                        LibraryId(UUID.randomUUID()), location, UUID.randomUUID())
                    if (state.binding(db, identity.id) == null) db.insertOrThrow("library_bindings", null,
                        state.locationValues(location).apply {
                            put("library_id", identity.id.value.toString()); put("generation", identity.generation.toString())
                        })
                    FileOutputStream(File(directory, "library-id")).use { owner ->
                        owner.write(identity.id.value.toString().toByteArray(Charsets.UTF_8))
                        owner.fd.sync()
                    }
                    val key = arrayOf(identity.id.value.toString())
                    val values = ContentValues().apply {
                        put("library_id", key[0]); put("import_generation", generation.toString())
                        put("imported_at", System.currentTimeMillis()); put("payload", payload)
                    }
                    val preferences = db.rawQuery("SELECT read_column_id,read_column_lookup FROM library_preferences WHERE library_id = ?", key).use {
                        if (it.moveToFirst() && !it.isNull(0)) Pair(it.getLong(0), it.getString(1)) else null
                    }
                    if (preferences != null) { values.put("read_column_id", preferences.first); values.put("read_column_lookup", preferences.second) }
                    db.execSQL("""INSERT INTO library_preferences(library_id,source_uuid,last_imported_at) VALUES(?,?,?)
                        ON CONFLICT(library_id) DO UPDATE SET source_uuid=excluded.source_uuid,last_imported_at=excluded.last_imported_at""",
                        arrayOf(key[0], parsed.sourceLibraryUuid?.toString(), System.currentTimeMillis()))
                    if (taskId != null) db.execSQL("UPDATE queued_tasks SET scope_library_id = ? WHERE task_id = ?", arrayOf(key[0], taskId.toString()))
                    // UPDATE retains the configured source column identity, including invalid configurations.
                    if (db.update("metadata_imports", values, "library_id = ?", key) == 0)
                        db.insertOrThrow("metadata_imports", null, values)
                    db.delete("metadata_books", "library_id = ?", key)
                    parsed.books.forEach { book -> db.insertOrThrow("metadata_books", null, ContentValues().apply {
                        put("library_id", key[0]); put("source_id", book.sourceId); put("source_uuid", book.sourceUuid.toString())
                        put("title", book.title); put("added_at", book.addedAt)
                    }) }
                    db.update("current_selection", ContentValues().apply { put("library_id", key[0]) }, "singleton = 1", null)
                    DurableTaskQueue.enqueueDownloadedChecks(db, identity.id)
                    if (taskId != null) DurableTaskQueue.completePublication(db, taskId)
                    db.setTransactionSuccessful()
                    identity
                } finally { db.endTransaction() }
                published = true
                // Publication is durable; collect superseded input generations while still owning
                // the importer lock. Cleanup must not leave a previous complete snapshot behind.
                try { collectUnpublished() } catch (_: Exception) {
                    android.util.Log.w("MetadataCache", "stage=old_generation_cleanup_failed")
                }
                committed
            } finally {
                if (!published) directory.deleteRecursively()
            }
        } }

    suspend fun currentImport(): ImportedLibrary? = withContext(io) {
        val db = database.readableDatabase
        // Serialize selection and generation reading, so switching cannot combine different libraries.
        db.beginTransactionNonExclusive()
        try {
            state.current(db)?.identity?.let { imported(db, it) }
        } finally { db.endTransaction() }
    }

    /** Compare-and-set prevents an old screen configuring another library or a newer import. */
    suspend fun selectReadColumn(expected: ImportedLibrary, column: CustomColumnId?): Boolean = withContext(io) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val identity = state.current(db)?.identity ?: return@withContext false
            if (identity != expected.identity) return@withContext false
            val current = imported(db, identity) ?: return@withContext false
            if (current.generation != expected.generation) return@withContext false
            if (column != null && current.metadata.columns.none {
                it.id == column && it.datatype == "bool" && it.supported
            }) return@withContext false
            db.update("metadata_imports", ContentValues().apply {
                if (column == null) { putNull("read_column_id"); putNull("read_column_lookup") }
                else { put("read_column_id", column.sourceId); put("read_column_lookup", column.lookupName) }
            }, "library_id = ?", arrayOf(identity.id.value.toString()))
            db.update("library_preferences", ContentValues().apply {
                if (column == null) { putNull("read_column_id"); putNull("read_column_lookup") }
                else { put("read_column_id", column.sourceId); put("read_column_lookup", column.lookupName) }
            }, "library_id = ?", arrayOf(identity.id.value.toString()))
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction() }
    }

    private fun imported(db: SQLiteDatabase, identity: LibraryIdentity): ImportedLibrary? = db.rawQuery(
        "SELECT import_generation, imported_at, payload, read_column_id, read_column_lookup FROM metadata_imports WHERE library_id = ?",
        arrayOf(identity.id.value.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val parsed = parsedLibraryFromJson(cursor.getString(2))
        val column = if (cursor.isNull(3)) null else CustomColumnId(cursor.getLong(3), cursor.getString(4))
        val status = when {
            column == null -> ReadColumnStatus.NOT_CONFIGURED
            parsed.columns.any { it.id == column && it.datatype == "bool" && it.supported } -> ReadColumnStatus.VALID
            else -> ReadColumnStatus.INVALID
        }
        ImportedLibrary(identity, UUID.fromString(cursor.getString(0)), cursor.getLong(1), parsed, column, status)
    }

    private fun compatibleBinding(db: SQLiteDatabase, location: LibraryLocation, parsed: ParsedLibrary): LibraryIdentity? {
        val args = state.locationValues(location)
        return db.rawQuery("""
            SELECT b.library_id FROM library_bindings b JOIN library_preferences m ON b.library_id = m.library_id
            WHERE b.backend = ? AND b.authority = ? AND b.root_id = ? AND b.account_id = ? AND b.drive_id = ?
            ORDER BY m.last_imported_at DESC, m.rowid DESC
        """.trimIndent(), arrayOf("backend", "authority", "root_id", "account_id", "drive_id")
            .map { args.getAsString(it) }.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val identity = requireNotNull(state.binding(db, LibraryId(UUID.fromString(cursor.getString(0)))))
                val old = imported(db, identity)?.metadata
                if (old == null) {
                    val sourceUuid = db.rawQuery("SELECT source_uuid FROM library_preferences WHERE library_id = ?", arrayOf(identity.id.value.toString())).use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
                    }
                    if (sourceUuid != parsed.sourceLibraryUuid?.toString() && (sourceUuid != null || parsed.sourceLibraryUuid != null)) continue
                    val copies = db.rawQuery("SELECT source_id,source_uuid FROM downloaded_copies WHERE library_id = ?", arrayOf(identity.id.value.toString())).use {
                        buildMap { while (it.moveToNext()) put(it.getLong(0), it.getString(1)) }
                    }
                    if (parsed.books.any { copies[it.sourceId]?.let { uuid -> uuid != it.sourceUuid.toString() } == true }) continue
                    return@use identity
                }
                if (old.sourceLibraryUuid != parsed.sourceLibraryUuid &&
                    (old.sourceLibraryUuid != null || parsed.sourceLibraryUuid != null)) continue
                val previous = old.books.associate { it.sourceId to it.sourceUuid }
                if (parsed.books.any { previous[it.sourceId]?.let { uuid -> uuid != it.sourceUuid } == true }) continue
                return@use identity
            }
            null
        }
    }

    /** Only UUID directories owned by this repository; never follows arbitrary stored source paths. */
    private fun collectUnpublished() {
        val retained = database.readableDatabase.rawQuery("SELECT import_generation FROM metadata_imports", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        root.listFiles().orEmpty().filter { file ->
            runCatching { UUID.fromString(file.name) }.isSuccess && file.name !in retained
        }.forEach { it.deleteRecursively() }
    }
}
