package io.github.chenxiex.calibrecloud.storage.covers

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Complete private images only. Reading never submits a task or contacts a backend. */
class CoverRepository(
    private val database: ApplicationStateDatabase,
    private val state: ApplicationStateRepository,
    private val filesDir: File,
    private val io: CoroutineDispatcher,
    private val maximumBytes: Long = 32L * 1024 * 1024,
) {
    private val access = Mutex()

    suspend fun read(book: BookKey): Bitmap? = withContext(io) { access.withLock {
        val db = database.writableDatabase
        val generation = db.rawQuery("SELECT file_generation FROM cover_cache WHERE $KEY", args(book)).use {
            if (it.moveToFirst()) UUID.fromString(it.getString(0)) else null
        } ?: return@withLock null
        val file = file(book, generation)
        if (!file.isFile || file.length() !in 1..MAX_ENCODED_BYTES) return@withLock null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..WIDTH || bounds.outHeight !in 1..HEIGHT) return@withLock null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return@withLock null
        db.update("cover_cache", ContentValues().apply { put("last_access", System.currentTimeMillis()) }, KEY, args(book))
        bitmap
    } }

    /** Cheap control-loop gate: never reparses the complete imported payload for each image block. */
    /** Whether a complete image is recorded for [book]; a resumed batch skips it. */
    internal suspend fun cached(book: BookKey): Boolean = withContext(io) {
        database.readableDatabase.rawQuery("SELECT 1 FROM cover_cache WHERE $KEY", args(book)).use { it.moveToFirst() }
    }

    internal suspend fun isCurrent(book: BookKey, importGeneration: UUID): Boolean = withContext(io) {
        val db = database.readableDatabase
        state.current(db)?.identity?.id == book.libraryId && db.rawQuery(
            "SELECT import_generation FROM metadata_imports WHERE library_id = ?",
            arrayOf(book.libraryId.value.toString())).use { it.moveToFirst() && it.getString(0) == importGeneration.toString() }
    }

    /**
     * Publishes one cover of the running batch [taskId] from [staged], a decoded, scaled and fsynced PNG
     * outside the cache. Moving it into the cache, syncing the directories and recording it happen under
     * the repository lock, so quota collection by a cover published at the same time never sees an
     * unrecorded generation. Returns false, leaving no file in the cache, when the gate refuses it. The
     * batch's own result is recorded when it ends.
     */
    suspend fun publish(book: BookKey, staged: File, importGeneration: UUID, taskId: UUID): Boolean = withContext(io) { access.withLock {
        require(staged.isFile && staged.length() in 1..MAX_ENCODED_BYTES)
        val generation = UUID.randomUUID()
        val complete = file(book, generation)
        require(complete.parentFile!!.mkdirs() || complete.parentFile!!.isDirectory)
        if (complete.exists() || !staged.renameTo(complete)) throw java.io.IOException()
        var recorded = false
        try {
            sync(complete.parentFile!!)
            sync(complete.parentFile!!.parentFile!!)
            sync(complete.parentFile!!.parentFile!!.parentFile!!)
            sync(staged.parentFile!!)
            recorded = record(book, generation, complete, importGeneration, taskId)
        } finally { if (!recorded) complete.delete() }
        recorded
    } }

    private fun record(book: BookKey, generation: UUID, complete: File, importGeneration: UUID, taskId: UUID): Boolean {
        val db = database.writableDatabase
        db.beginTransaction()
        val published = try {
            if (state.current(db)?.identity?.id != book.libraryId) return false
            val currentImport = db.rawQuery("SELECT import_generation FROM metadata_imports WHERE library_id = ?",
                arrayOf(book.libraryId.value.toString())).use { it.moveToFirst() && it.getString(0) == importGeneration.toString() }
            val identity = db.rawQuery("SELECT source_uuid FROM metadata_books WHERE library_id = ? AND source_id = ?",
                arrayOf(book.libraryId.value.toString(), book.sourceId.toString())).use { it.moveToFirst() && it.getString(0) == book.sourceUuid.toString() }
            val allowed = db.rawQuery("SELECT control FROM queued_tasks WHERE task_id = ? AND revoked = 0", arrayOf(taskId.toString())).use {
                it.moveToFirst() && it.isNull(0)
            }
            if (!currentImport || !identity || !allowed) return false
            val values = ContentValues().apply {
                put("library_id", book.libraryId.value.toString()); put("source_id", book.sourceId)
                put("source_uuid", book.sourceUuid.toString()); put("file_generation", generation.toString())
                put("size_bytes", complete.length()); put("last_access", System.currentTimeMillis())
            }
            if (db.update("cover_cache", values, KEY, args(book)) == 0) db.insertOrThrow("cover_cache", null, values)
            db.setTransactionSuccessful()
            true
        } finally { db.endTransaction() }
        if (published) {
            // Quota maintenance cannot undo an already committed publication.
            try { collect() } catch (_: Exception) {
                android.util.Log.w("CoverCache", "stage=quota_cleanup_failed")
            }
        }
        return published
    }

    private fun sync(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    /** Only cover generations are quota-managed; books and protected recovery data are outside this root. */
    private fun collect() {
        val db = database.writableDatabase
        var retainedBytes = 0L
        val retained = mutableSetOf<String>()
        db.rawQuery("SELECT file_generation,size_bytes FROM cover_cache ORDER BY last_access DESC,rowid DESC", null).use {
            while (it.moveToNext()) {
                val generation = it.getString(0)
                if (retainedBytes + it.getLong(1) <= maximumBytes) {
                    retained.add(generation); retainedBytes += it.getLong(1)
                } else db.delete("cover_cache", "file_generation = ?", arrayOf(generation))
            }
        }
        val root = privatePath("covers")
        root.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory && uuid(it.name) }.forEach { library ->
            library.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isFile &&
                it.extension == "png" && uuid(it.nameWithoutExtension) && it.nameWithoutExtension !in retained }.forEach { it.delete() }
        }
    }

    internal fun file(book: BookKey, generation: UUID) = privatePath("covers/${book.libraryId.value}/$generation.png")

    internal fun privatePath(relative: String): File {
        var current = filesDir
        require(!Files.isSymbolicLink(current.toPath()))
        relative.split('/').forEach { part ->
            require(part.isNotEmpty() && part != "." && part != "..")
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath()))
        }
        return current
    }

    private fun uuid(value: String) = runCatching { UUID.fromString(value) }.isSuccess
    private fun args(book: BookKey) = arrayOf(book.libraryId.value.toString(), book.sourceId.toString(), book.sourceUuid.toString())

    companion object {
        const val WIDTH = 256
        const val HEIGHT = 384
        const val MAX_ENCODED_BYTES = 12L * 1024 * 1024
        private const val KEY = "library_id = ? AND source_id = ? AND source_uuid = ?"
    }
}
