package io.github.chenxiex.calibrecloud.storage.cache

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.CopyMaintenance
import io.github.chenxiex.calibrecloud.storage.api.StorageOperationResult
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** LIBRARY deletes one listed library: everything OTHER_LIBRARIES clears for it, plus its settings and list entry (Q59). */
enum class CleanupKind { COPIES, METADATA, OTHER_LIBRARIES, LIBRARY }

@ConsistentCopyVisibility
data class CleanupPlan internal constructor(
    val kind: CleanupKind,
    /** The current selection the plan was made under; null when no library is current. */
    val selectionToken: UUID?,
    val libraries: Set<LibraryId>,
    val copies: Set<CopyKey>,
    val bytes: Long,
    internal val books: Set<BookKey> = emptySet(),
    internal val formats: Set<BookFormat>? = null,
    internal val currentLibrary: LibraryId? = null,
    /** LIBRARY only: the deleted location, and whether it is the current library. */
    val location: LibraryLocation? = null,
    internal val deletesCurrent: Boolean = false,
)

/**
 * Private cache maintenance, with no source dependency. Preview freezes book/UUID/format scope;
 * confirmation rechecks selection in the removal transaction. Null formats means all formats.
 * The transaction revokes existing producers and removes query pointers before deleting bytes.
 * A durable journal blocks matching new submissions until safe-boundary deletion finishes. The shared queue
 * lock prevents old producers writing files after deletion; revoked tasks cannot retry or publish.
 * Interrupted deletion is retried before dispatch. Protected write evidence is never enumerated.
 * When no library is current, or the current one has no import yet, other-library cleanup includes all
 * persisted library bindings while retaining the library list and the library being added.
 * Deleting a library also covers every binding at its location and, when it is current, the tasks of
 * the current selection, which it removes; no library becomes current in its place.
 */
class CacheMaintenance(
    private val database: ApplicationStateDatabase,
    private val state: ApplicationStateRepository,
    private val queue: DurableTaskQueue,
    private val filesDir: File,
    private val io: CoroutineDispatcher,
) : CopyMaintenance {
    private val maintenance = Mutex()

    override suspend fun removeCopy(key: CopyKey): StorageOperationResult = operation {
        previewCopies(setOf(key.book), setOf(key.format))?.let { execute(it) } ?: false
    }

    override suspend fun clearMetadata(libraryId: LibraryId): StorageOperationResult = operation {
        previewMetadata()?.takeIf { it.libraries == setOf(libraryId) }?.let { execute(it) } ?: false
    }

    private suspend fun operation(action: suspend () -> Boolean): StorageOperationResult = try {
        if (action()) StorageOperationResult.Completed else StorageOperationResult.Failed(StorageError(StorageErrorKind.LOCAL_IO))
    } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { StorageOperationResult.Failed(StorageError(StorageErrorKind.LOCAL_IO)) }

    suspend fun previewCopies(books: Set<BookKey>, formats: Set<BookFormat>? = null): CleanupPlan? = withContext(io) {
        require(books.isNotEmpty() && (formats == null || formats.isNotEmpty()))
        preview(CleanupKind.COPIES, FrozenSet(books), formats?.let { FrozenSet(it) })
    }
    suspend fun previewMetadata(): CleanupPlan? = withContext(io) { preview(CleanupKind.METADATA) }
    suspend fun previewOtherLibraries(): CleanupPlan? = withContext(io) { preview(CleanupKind.OTHER_LIBRARIES) }
    suspend fun previewLibrary(location: LibraryLocation): CleanupPlan? = withContext(io) { preview(CleanupKind.LIBRARY, location = location) }

    private fun bindingsAt(db: SQLiteDatabase, location: LibraryLocation): Set<LibraryId> = db.rawQuery(
        "SELECT library_id FROM library_bindings WHERE backend = ? AND location_key = ?", state.locationArgs(location),
    ).use { buildSet { while (it.moveToNext()) add(LibraryId(UUID.fromString(it.getString(0)))) } }

    private fun listed(db: SQLiteDatabase, location: LibraryLocation) = db.rawQuery(
        "SELECT 1 FROM configured_libraries WHERE backend = ? AND location_key = ?", state.locationArgs(location),
    ).use { it.moveToFirst() }

    private suspend fun preview(kind: CleanupKind, books: Set<BookKey> = emptySet(), formats: Set<BookFormat>? = null,
                                location: LibraryLocation? = null): CleanupPlan? {
        val db = database.readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val selected = state.current(db)
            if (kind == CleanupKind.LIBRARY) {
                if (location == null || !listed(db, location)) return null
                val deletesCurrent = selected?.location == location
                val libraries = bindingsAt(db, location)
                val plan = CleanupPlan(kind, selected?.token, FrozenSet(libraries), FrozenSet(allCopyKeys(db, libraries)), 0,
                    currentLibrary = selected?.identity?.id, location = location, deletesCurrent = deletesCurrent)
                return plan.copy(bytes = paths(db, plan, affectedTasks(db, plan)).distinct().sumOf { size(privateFile(it)) })
            }
            // With no current library, every library is another library.
            if (selected == null && kind != CleanupKind.OTHER_LIBRARIES) return null
            val current = selected?.identity?.id
            if (kind != CleanupKind.OTHER_LIBRARIES && current == null) return null
            if (books.any { it.libraryId != current }) return null
            val libraries = if (kind == CleanupKind.OTHER_LIBRARIES) db.rawQuery(
                if (current == null) "SELECT library_id FROM library_bindings" else "SELECT library_id FROM library_bindings WHERE library_id != ?",
                current?.let { arrayOf(it.value.toString()) }).use { buildSet { while (it.moveToNext()) add(LibraryId(UUID.fromString(it.getString(0)))) } }
                else setOf(requireNotNull(current))
            val copies = (if (kind == CleanupKind.METADATA) emptySet() else allCopyKeys(db, libraries)).filter { kind != CleanupKind.COPIES || (it.book in books && (formats == null || it.format in formats)) }.toSet()
            val plan = CleanupPlan(kind, selected?.token, FrozenSet(libraries), FrozenSet(copies), 0, books, formats, current)
            val paths = paths(db, plan, affectedTasks(db, plan))
            val size = paths.distinct().sumOf { size(privateFile(it)) }
            return plan.copy(bytes = size)
        } finally { db.endTransaction() }
    }

    suspend fun execute(plan: CleanupPlan): Boolean = withContext(io) { maintenance.withLock {
        val db = database.writableDatabase
        db.transaction {
            val selected = state.current(db)
            val current = selected?.identity?.id
            if (selected?.token != plan.selectionToken || current != plan.currentLibrary) return@withLock false
            if (plan.kind == CleanupKind.LIBRARY) {
                val location = plan.location ?: return@withLock false
                if (!listed(db, location) || bindingsAt(db, location) != plan.libraries ||
                    (selected?.location == location) != plan.deletesCurrent) return@withLock false
            } else if (plan.kind == CleanupKind.OTHER_LIBRARIES && current in plan.libraries) return@withLock false
            else if (plan.kind != CleanupKind.OTHER_LIBRARIES && (current == null || plan.libraries != setOf(current))) return@withLock false
            if (DurableTaskQueue.cacheCleanupPending(db)) return@withLock false
            val tasks = affectedTasks(db, plan)
            val paths = paths(db, plan, tasks)
            val id = UUID.randomUUID().toString()
            val journal = JSONObject().put("paths", JSONArray(paths.filterNot { it.startsWith("books/") })).put("libraries", JSONArray(plan.libraries.map { it.value.toString() }))
                .put("tasks", JSONArray(tasks.map { it.value.toString() }))
                .put("retire", JSONArray(retiredGenerations(db, plan, tasks)))
                .put("kind", plan.kind.name).put("token", plan.selectionToken?.toString() ?: JSONObject.NULL)
                .put("books", JSONArray(plan.books.map { "${it.libraryId.value}/${it.sourceId}/${it.sourceUuid}" }))
                .put("formats", plan.formats?.let { JSONArray(it.map { format -> format.value }) } ?: JSONObject.NULL)
            db.insertOrThrow("cache_cleanup", null, ContentValues().apply { put("cleanup_id", id); put("payload", journal.toString()) })
            tasks.forEach { DurableTaskQueue.revoke(db, it) }
            if (plan.kind != CleanupKind.COPIES) plan.libraries.forEach { library ->
                val args = arrayOf(library.value.toString())
                db.delete("metadata_books", "library_id = ?", args)
                db.delete("metadata_imports", "library_id = ?", args)
                db.delete("cover_cache", "library_id = ?", args)
                db.update("downloaded_copies", ContentValues().apply { put("source_availability", "unconfirmed") }, "library_id = ?", args)
            }
            if (plan.kind == CleanupKind.LIBRARY) {
                plan.libraries.forEach { library ->
                    val args = arrayOf(library.value.toString())
                    listOf("library_preferences", "search_history", "last_opened").forEach { db.delete(it, "library_id = ?", args) }
                }
                val location = requireNotNull(plan.location)
                db.delete("configured_libraries", "backend = ? AND location_key = ?", state.locationArgs(location))
                if (plan.deletesCurrent) db.delete("current_selection", "singleton = 1", null)
            }
            if (plan.kind != CleanupKind.METADATA) {
                val keys = allCopyKeys(db, plan.libraries).filter { plan.kind != CleanupKind.COPIES ||
                    (it.book in plan.books && (plan.formats == null || it.format in plan.formats)) }
                keys.forEach { db.delete("downloaded_copies", COPY_KEY, copyArgs(it)) }
            }
        }
        queue.invalidateObservers()
        try {
            queue.executionLock.withLock { recoverLocked() }
            true
        } catch (_: java.io.IOException) { false }
    } }

    /** Caller owns queue.executionLock. Recovery does not consult current selection or a backend. */
    internal suspend fun recoverLocked(): Unit = withContext(io) {
        val db = database.writableDatabase
        journalAbandonedInputs(db)
        journalPrunedTasks(db)
        val journals = db.rawQuery("SELECT cleanup_id,payload FROM cache_cleanup", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0) to JSONObject(it.getString(1))) }
        }
        journals.forEach { (id, payload) ->
            val paths = payload.getJSONArray("paths")
            for (index in 0 until paths.length()) delete(privateFile(paths.getString(index)))
            val libraries = payload.getJSONArray("libraries")
            if (payload.optString("kind") in setOf("OTHER_LIBRARIES", "LIBRARY")) {
                for (index in 0 until libraries.length()) state.collectUnreferenced(LibraryId(UUID.fromString(libraries.getString(index))))
            } else {
                val retired = payload.optJSONArray("retire") ?: JSONArray()
                state.retireCopies(buildSet {
                    for (index in 0 until retired.length()) {
                        val parts = retired.getString(index).split('/')
                        add(CompleteCopyLocation(LibraryId(UUID.fromString(parts[0])), UUID.fromString(parts[1])))
                    }
                })
            }
            val tasks = payload.getJSONArray("tasks")
            db.beginTransaction()
            try {
                for (index in 0 until tasks.length()) {
                    val task = TaskId(UUID.fromString(tasks.getString(index)))
                    DurableTaskQueue.revoke(db, task)
                    db.update("queued_tasks", ContentValues().apply {
                        putNull("checkpoint"); putNull("checkpoint_backend"); putNull("checkpoint_version"); putNull("control")
                    }, "task_id = ?", arrayOf(task.value.toString()))
                }
                db.delete("cache_cleanup", "cleanup_id = ?", arrayOf(id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        if (journals.isNotEmpty()) {
            queue.invalidateObservers()
            journalAbandonedInputs(db)
            journalPrunedTasks(db)
            if (DurableTaskQueue.cacheCleanupPending(db)) recoverLocked()
        }
    }

    /**
     * Removes finished tasks beyond the kept history (Q74) and journals their task-keyed temporary
     * files in the same transaction, so the deletion below completes even after process death.
     * Runs only with no other cleanup pending, whose journals may still name these tasks.
     */
    private fun journalPrunedTasks(db: SQLiteDatabase) {
        db.transaction {
            if (DurableTaskQueue.cacheCleanupPending(db)) return
            val tasks = queue.pruneFinished(db)
            if (tasks.isNotEmpty()) journalTaskInputs(db, tasks, revoke = false)
        }
    }

    /**
     * Old unbound candidate inputs have no published pointers and their selection token can never
     * become active again. Reclaim this ordinary temporary data even when legacy ownership cannot
     * be reconstructed; never infer a library or touch complete metadata/books/protection domains.
     */
    private fun journalAbandonedInputs(db: SQLiteDatabase) {
        db.transaction {
            if (DurableTaskQueue.cacheCleanupPending(db)) return
            val tokens = setOfNotNull(state.current(db)?.token, state.addition(db)?.token)
            val tasks = db.rawQuery("SELECT task_id,record FROM queued_tasks WHERE scope_library_id IS NULL AND revoked = 0", null).use {
                buildList { while (it.moveToNext()) {
                    val record = TaskCodec.decode(it.getString(1))
                    val request = record.submission.request as? TaskRequest.CandidateConfiguration ?: continue
                    if (request.context.selectionToken !in tokens) add(TaskId(UUID.fromString(it.getString(0))))
                } }
            }
            if (tasks.isNotEmpty()) journalTaskInputs(db, tasks, revoke = true)
        }
    }

    /**
     * Journals every temporary file keyed by [tasks] in the caller's transaction. With [revoke], the
     * tasks still exist and are withdrawn now and again when the journal completes; otherwise they
     * were already removed from the queue.
     */
    private fun journalTaskInputs(db: SQLiteDatabase, tasks: List<TaskId>, revoke: Boolean) {
        val paths = tasks.flatMap { listOf("book-staging/${it.value}", "cover-staging/${it.value}",
            "snapshots/local/${it.value}", "snapshots/onedrive/${it.value}",
            "onedrive-browser/${it.value}.json", "onedrive-browser/${it.value}.part") }
        val named = if (revoke) tasks.map { it.value.toString() } else emptyList()
        val payload = JSONObject().put("paths", JSONArray(paths)).put("libraries", JSONArray())
            .put("tasks", JSONArray(named)).put("kind", "INPUTS")
        db.insertOrThrow("cache_cleanup", null, ContentValues().apply {
            put("cleanup_id", UUID.randomUUID().toString()); put("payload", payload.toString())
        })
        if (revoke) tasks.forEach { DurableTaskQueue.revoke(db, it) }
    }

    private fun affectedTasks(db: SQLiteDatabase, plan: CleanupPlan): Set<TaskId> = db.rawQuery("SELECT record,scope_library_id FROM queued_tasks", null).use {
        buildSet { while (it.moveToNext()) {
            val record = TaskCodec.decode(it.getString(0))
            val request = record.submission.request
            val scope = if (it.isNull(1)) null else LibraryId(UUID.fromString(it.getString(1)))
            val candidate = request as? TaskRequest.CandidateConfiguration
            val ownToken = plan.selectionToken != null && candidate?.context?.selectionToken == plan.selectionToken
            val belongs = request.libraryId in plan.libraries || scope in plan.libraries ||
                (ownToken && (plan.kind == CleanupKind.COPIES || plan.kind == CleanupKind.METADATA || plan.deletesCurrent))
            if (!belongs) continue
            val match = when (plan.kind) {
                CleanupKind.COPIES -> when (request) {
                    is TaskRequest.FormatCopy -> matches(CopyKey(request.resource.book, request.resource.format), plan)
                    is TaskRequest.FormatCheck -> matches(request.key, plan)
                    else -> false
                }
                CleanupKind.METADATA -> request is TaskRequest.CoverLoad ||
                    (request is TaskRequest.MetadataSync && request.freshness == SnapshotFreshness.CurrentSource) ||
                    (candidate != null && candidate.operation in setOf("local_snapshot", "onedrive_snapshot"))
                CleanupKind.OTHER_LIBRARIES, CleanupKind.LIBRARY -> when {
                    request is TaskRequest.ReadStatusWrite -> record.commit == CommitState.NotCommitted
                    request is TaskRequest.MetadataSync && request.freshness is SnapshotFreshness.AfterWrite -> {
                        val parent = request.freshness.writeTaskId
                        db.rawQuery("SELECT record FROM queued_tasks WHERE task_id = ?", arrayOf(parent.value.toString())).use { source ->
                            !source.moveToFirst() || TaskCodec.decode(source.getString(0)).commit == CommitState.NotCommitted
                        }
                    }
                    else -> true
                }
            }
            if (match) add(record.id)
        } }
    }

    private fun matches(key: CopyKey, plan: CleanupPlan) = key.book in plan.books && (plan.formats == null || key.format in plan.formats)

    private fun paths(db: SQLiteDatabase, plan: CleanupPlan, tasks: Set<TaskId>): List<String> = buildList {
        if (plan.kind != CleanupKind.COPIES) plan.libraries.forEach { library ->
            val args = arrayOf(library.value.toString())
            db.rawQuery("SELECT import_generation FROM metadata_imports WHERE library_id = ?", args).use {
                while (it.moveToNext()) add("metadata/${UUID.fromString(it.getString(0))}")
            }
            privateFile("metadata").listFiles().orEmpty().filter { directory ->
                runCatching { UUID.fromString(directory.name) }.isSuccess && !Files.isSymbolicLink(directory.toPath())
            }.forEach { directory ->
                val owner = File(directory, "library-id")
                if (owner.isFile && !Files.isSymbolicLink(owner.toPath()) && owner.readText() == library.value.toString())
                    add("metadata/${directory.name}")
            }
            add("covers/${library.value}")
        }
        // Book generations are retired through the shared handle factory, never unlinked here.
        tasks.forEach { task ->
            add("book-staging/${task.value}"); add("cover-staging/${task.value}")
            add("snapshots/local/${task.value}"); add("snapshots/onedrive/${task.value}")
            if (plan.kind == CleanupKind.OTHER_LIBRARIES || plan.kind == CleanupKind.LIBRARY) add("onedrive-browser/${task.value}.json")
        }
        if (plan.kind == CleanupKind.OTHER_LIBRARIES || plan.kind == CleanupKind.LIBRARY) {
            plan.libraries.forEach { add("books/${it.value}") }
        } else if (plan.kind == CleanupKind.COPIES) {
            retiredGenerations(db, plan, tasks).forEach { generation ->
                val parts = generation.split('/')
                add("books/${parts[0]}/${parts[1]}.book")
            }
        }
    }

    private fun retiredGenerations(db: SQLiteDatabase, plan: CleanupPlan, tasks: Set<TaskId>): List<String> = buildList {
        if (plan.kind == CleanupKind.METADATA) return@buildList
        allCopyKeys(db, plan.libraries).filter { plan.kind != CleanupKind.COPIES || matches(it, plan) }.forEach { key ->
            db.rawQuery("SELECT file_generation FROM downloaded_copies WHERE $COPY_KEY", copyArgs(key)).use {
                if (it.moveToFirst()) add("${key.book.libraryId.value}/${UUID.fromString(it.getString(0))}")
            }
        }
        tasks.forEach { task -> db.rawQuery("SELECT record,checkpoint FROM queued_tasks WHERE task_id = ?", arrayOf(task.value.toString())).use {
            if (it.moveToFirst() && !it.isNull(1)) {
                val request = TaskCodec.decode(it.getString(0)).submission.request as? TaskRequest.FormatCopy
                if (request != null) add("${request.libraryId.value}/${UUID.fromString(it.getString(1))}")
            }
        } }
    }

    private fun allCopyKeys(db: SQLiteDatabase, libraries: Set<LibraryId>): Set<CopyKey> = buildSet {
        libraries.forEach { library -> db.rawQuery("SELECT source_id,source_uuid,format FROM downloaded_copies WHERE library_id = ?",
            arrayOf(library.value.toString())).use { while (it.moveToNext()) add(CopyKey(
                BookKey(library, it.getLong(0), UUID.fromString(it.getString(1))), BookFormat.parse(it.getString(2)))) } }
    }

    private fun privateFile(relative: String): File {
        val parts = relative.split('/')
        require(parts.first() in setOf("metadata", "covers", "books", "book-staging", "cover-staging", "snapshots", "onedrive-browser"))
        var file = filesDir
        parts.forEach { part ->
            require(part.isNotBlank() && part != "." && part != "..")
            if (Files.isSymbolicLink(file.toPath())) throw java.io.IOException("Linked cache path")
            file = File(file, part)
        }
        if (Files.isSymbolicLink(file.toPath())) throw java.io.IOException("Linked cache path")
        return file
    }
    private fun size(file: File): Long {
        if (Files.isSymbolicLink(file.toPath())) return 0
        return if (file.isDirectory) file.listFiles().orEmpty().sumOf { size(it) } else if (file.isFile) file.length() else 0
    }
    private fun delete(file: File) {
        if (!file.exists()) return
        if (Files.isSymbolicLink(file.toPath())) throw java.io.IOException("Linked cache path")
        if (file.isDirectory) file.listFiles().orEmpty().forEach { delete(it) }
        if (!file.delete()) throw java.io.IOException("Cache deletion failed")
    }
    private fun copyArgs(key: CopyKey) = arrayOf(key.book.libraryId.value.toString(), key.book.sourceId.toString(), key.book.sourceUuid.toString(), key.format.value)
    companion object { private const val COPY_KEY = "library_id = ? AND source_id = ? AND source_uuid = ? AND format = ?" }
}
