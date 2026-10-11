package io.github.chenxiex.calibrecloud.state

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.api.CandidateContext
import io.github.chenxiex.calibrecloud.storage.cache.ApplicationCopyHandleFactory
import io.github.chenxiex.calibrecloud.storage.cache.HandleOpenResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import java.util.UUID
import java.io.IOException

data class LibrarySelection(
    val token: UUID,
    val location: LibraryLocation?,
    val identity: LibraryIdentity?,
    val backend: BackendKind = requireNotNull(location).backend,
    val authorizationId: UUID? = null,
)

/**
 * A library in the user's list (R03). [accessKey] is the backend's opaque reference to what authorizes
 * the location, such as the local directory grant; [displayName] is the root directory's name when known.
 */
data class ConfiguredLibrary(val location: LibraryLocation, val displayName: String?, val accessKey: String?)

/**
 * The library being added. [token] scopes the directory listings made for it; [location] stays null
 * until a root is chosen. Nothing about the addition changes the current selection before it completes.
 */
data class LibraryAddition(
    val token: UUID,
    val backend: BackendKind,
    val authorizationId: UUID?,
    val location: LibraryLocation?,
    val displayName: String?,
    val accessKey: String?,
) {
    val context: CandidateContext? get() = authorizationId?.let { CandidateContext(token, backend, it) }
}

/**
 * All public suspend operations dispatch database/file work off the caller's thread.
 * Selection tokens revoke old UI intents and candidate requests: the queue and publishers compare them
 * before publishing, and run library tasks only for current().identity. Selecting never validates a source.
 * New binding is reserved for a successful importer; re-selection restores only a previously valid private import.
 */
class ApplicationStateRepository(
    private val database: ApplicationStateDatabase,
    private val files: ApplicationCopyHandleFactory,
    private val ioDispatcher: CoroutineDispatcher,
) : CompleteCopyQuery {
    /** Shared with ordinary reads to keep query/open atomic relative to manifest replacement. */
    val copyAccess = Mutex()
    suspend fun select(location: LibraryLocation): LibrarySelection = withContext(ioDispatcher) {
        transaction { selectInTransaction(this, location) }
    }

    /** Libraries in the order they were added. */
    suspend fun libraries(): List<ConfiguredLibrary> = withContext(ioDispatcher) {
        database.readableDatabase.query("configured_libraries", null, null, null, null, null, "position, rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(ConfiguredLibrary(cursor.location(), cursor.optionalText("display_name"),
                cursor.optionalText("access_key"))) }
        }
    }

    /** The access key [location] was added with; null when it is not in the list or needs none. */
    suspend fun accessKey(location: LibraryLocation): String? = withContext(ioDispatcher) {
        database.readableDatabase.query("configured_libraries", arrayOf("access_key"), "backend = ? AND location_key = ?",
            locationArgs(location), null, null, null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
    }

    /** Replaces the access key of a listed library, as re-authorizing does; returns the old key. */
    suspend fun replaceAccessKey(location: LibraryLocation, accessKey: String): Result<String?> = withContext(ioDispatcher) {
        transaction {
            val old = query("configured_libraries", arrayOf("access_key"), "backend = ? AND location_key = ?", locationArgs(location),
                null, null, null).use { if (!it.moveToFirst()) return@transaction Result.failure(NoSuchElementException())
                    else if (it.isNull(0)) null else it.getString(0) }
            update("configured_libraries", ContentValues().apply { put("access_key", accessKey) },
                "backend = ? AND location_key = ?", locationArgs(location))
            Result.success(old)
        }
    }

    /** Whether some listed library or the addition still uses [accessKey]. */
    suspend fun accessKeyInUse(accessKey: String): Boolean = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("""SELECT 1 FROM configured_libraries WHERE access_key = ?
            UNION SELECT 1 FROM library_addition WHERE access_key = ?""", arrayOf(accessKey, accessKey)).use { it.moveToFirst() }
    }

    /** Makes a listed library current, as choosing it in the list does; null when it is not listed. */
    suspend fun switchTo(location: LibraryLocation): LibrarySelection? = withContext(ioDispatcher) {
        transaction {
            if (!listed(this, location)) return@transaction null
            selectInTransaction(this, location)
        }
    }

    suspend fun addition(): LibraryAddition? = withContext(ioDispatcher) { addition(database.readableDatabase) }

    /**
     * Starts adding a library of [backend], replacing an unfinished addition; listings bound to the old
     * token stop being active. [authorizationId] is the sign-in session listings run under, if any.
     * Returns the replaced addition so the caller can release what authorized it.
     */
    suspend fun beginAddition(backend: BackendKind, authorizationId: UUID?): Pair<LibraryAddition, LibraryAddition?> = withContext(ioDispatcher) {
        transaction {
            val previous = addition(this)
            val next = LibraryAddition(UUID.randomUUID(), backend, authorizationId, null, null, null)
            delete("library_addition", null, null)
            insertOrThrow("library_addition", null, ContentValues().apply {
                put("singleton", 1); put("token", next.token.toString()); put("backend", LocationKeys.backendCode(backend))
                if (authorizationId == null) putNull("authorization_id") else put("authorization_id", authorizationId.toString())
            })
            next to previous
        }
    }

    /**
     * Records the chosen root of the addition [token]: [location], its [displayName] and the [accessKey]
     * that authorizes it. A replaced or finished addition is rejected. Returns the replaced access key.
     */
    suspend fun chooseAddition(token: UUID, location: LibraryLocation, displayName: String?, accessKey: String?): Result<String?> =
        withContext(ioDispatcher) {
            transaction {
                val current = addition(this)
                if (current?.token != token || current.backend != location.backend) return@transaction Result.failure(IllegalStateException())
                update("library_addition", locationValues(location).apply {
                    if (displayName == null) putNull("display_name") else put("display_name", displayName)
                    if (accessKey == null) putNull("access_key") else put("access_key", accessKey)
                }, "singleton = 1", null)
                Result.success(current.accessKey?.takeIf { it != accessKey })
            }
        }

    /** Returns to choosing a root for the addition [token], keeping its backend and session. */
    suspend fun clearAdditionRoot(token: UUID): LibraryAddition? = withContext(ioDispatcher) {
        transaction {
            val current = addition(this)?.takeIf { it.token == token } ?: return@transaction null
            update("library_addition", ContentValues().apply {
                putNull("location_key"); putNull("display_name"); putNull("access_key")
            }, "singleton = 1", null)
            current
        }
    }

    /** Abandons the addition; returns it so the caller can release what authorized it. */
    suspend fun cancelAddition(): LibraryAddition? = withContext(ioDispatcher) {
        transaction { addition(this).also { delete("library_addition", null, null) } }
    }

    /**
     * Completes the addition [token]: lists its location (or, when already listed, refreshes the name and
     * access key) and makes it current in one transaction. Returns the selection and the access key
     * the listed entry no longer uses, or null when the addition was replaced or has no root yet.
     */
    suspend fun completeAddition(token: UUID): Pair<LibrarySelection, String?>? = withContext(ioDispatcher) {
        transaction {
            val current = addition(this)?.takeIf { it.token == token } ?: return@transaction null
            val location = current.location ?: return@transaction null
            val old = query("configured_libraries", arrayOf("access_key"), "backend = ? AND location_key = ?", locationArgs(location),
                null, null, null).use { if (it.moveToFirst()) (if (it.isNull(0)) null else it.getString(0)) to true else null to false }
            val values = ContentValues().apply {
                put("display_name", current.displayName)
                if (current.accessKey == null) putNull("access_key") else put("access_key", current.accessKey)
            }
            if (old.second) {
                update("configured_libraries", values, "backend = ? AND location_key = ?", locationArgs(location))
            } else {
                val position = rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM configured_libraries", null).use {
                    it.moveToFirst(); it.getInt(0)
                }
                insertOrThrow("configured_libraries", null, locationValues(location).apply { putAll(values); put("position", position) })
            }
            delete("library_addition", null, null)
            selectInTransaction(this, location) to old.first?.takeIf { it != current.accessKey }
        }
    }

    private fun listed(db: SQLiteDatabase, location: LibraryLocation): Boolean =
        db.query("configured_libraries", arrayOf("position"), "backend = ? AND location_key = ?", locationArgs(location),
            null, null, null).use { it.moveToFirst() }

    internal fun addition(db: SQLiteDatabase): LibraryAddition? = db.query("library_addition", null, "singleton = 1", null, null, null, null).use {
        if (!it.moveToFirst()) null else {
            val backend = LocationKeys.backend(it.text("backend"))
            LibraryAddition(UUID.fromString(it.text("token")), backend, it.optionalText("authorization_id")?.let(UUID::fromString),
                it.optionalText("location_key")?.let { key -> LocationKeys.decode(backend, key) },
                it.optionalText("display_name"), it.optionalText("access_key"))
        }
    }

    /**
     * Binds the selected position to a new authorization under a new token, so requests bound to the old
     * authorization are revoked; cannot overwrite a concurrent backend/directory selection.
     */
    suspend fun reauthorizeCandidate(selectionToken: UUID, authorizationId: UUID): CandidateContext? = withContext(ioDispatcher) {
        transaction {
            val selected = current(this) ?: return@transaction null
            if (selected.token != selectionToken || selected.location == null) return@transaction null
            val next = CandidateContext(UUID.randomUUID(), selected.backend, authorizationId)
            update("current_selection", ContentValues().apply {
                put("token", next.selectionToken.toString()); put("authorization_id", authorizationId.toString())
            }, "singleton = 1", null)
            next
        }
    }

    suspend fun current(): LibrarySelection? = withContext(ioDispatcher) { current(database.readableDatabase) }

    /** Global R19 setting; retained independently of library selection and cache cleanup. */
    suspend fun startupEnabled(): Boolean = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("SELECT startup_sync FROM application_settings WHERE singleton = 1", null).use {
            it.moveToFirst() && it.getInt(0) == 1
        }
    }

    suspend fun setStartupEnabled(enabled: Boolean): Unit = withContext(ioDispatcher) {
        transaction {
            update("application_settings", ContentValues().apply { put("startup_sync", if (enabled) 1 else 0) },
                "singleton = 1", null)
        }
    }

    /** Global R24 format order, highest first; formats it omits follow by name. Survives cache cleanup. */
    suspend fun formatPriority(): List<BookFormat> = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("SELECT format_priority FROM application_settings WHERE singleton = 1", null).use {
            if (!it.moveToFirst()) return@use emptyList()
            it.getString(0).split(',').filter { name -> name.isNotEmpty() }.map(BookFormat::parse)
        }
    }

    suspend fun setFormatPriority(order: List<BookFormat>): Unit = withContext(ioDispatcher) {
        require(order.distinct().size == order.size)
        transaction {
            update("application_settings", ContentValues().apply { put("format_priority", order.joinToString(",") { it.value }) },
                "singleton = 1", null)
        }
    }

    /** The library page's saved view and filters, opaque to this layer; null until first saved. Survives cache cleanup. */
    suspend fun libraryView(): String? = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("SELECT library_view FROM application_settings WHERE singleton = 1", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    }

    suspend fun setLibraryView(value: String): Unit = withContext(ioDispatcher) {
        transaction {
            update("application_settings", ContentValues().apply { put("library_view", value) }, "singleton = 1", null)
        }
    }

    suspend fun binding(id: LibraryId): LibraryIdentity? = withContext(ioDispatcher) {
        binding(database.readableDatabase, id)
    }

    /** Historical incarnations at this stable location; the importer must verify compatibility before reuse. */
    suspend fun bindingsAt(location: LibraryLocation): List<LibraryIdentity> = withContext(ioDispatcher) {
        database.readableDatabase.query(
            "library_bindings", null, "backend = ? AND location_key = ?", locationArgs(location), null, null, "library_id",
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(LibraryIdentity(
                LibraryId(UUID.fromString(cursor.text("library_id"))), cursor.location(), UUID.fromString(cursor.text("generation")),
            ))
        } }
    }

    /** Reuse is explicit after validation. Stale validation returns false without changing any state. */
    suspend fun bindValidated(selectionToken: UUID, identity: LibraryIdentity): Boolean = withContext(ioDispatcher) {
        transaction {
            val selection = current(this) ?: return@transaction false
            if (selection.token != selectionToken) return@transaction false
            require(selection.location == identity.location)
            val existing = binding(this, identity.id)
            require(existing == null || existing == identity) { "Library identity cannot be rebound" }
            if (existing == null) {
                insertOrThrow("library_bindings", null, locationValues(identity.location).apply {
                    put("library_id", identity.id.value.toString())
                    put("generation", identity.generation.toString())
                })
            }
            update("current_selection", ContentValues().apply { put("library_id", identity.id.value.toString()) }, "singleton = 1", null)
            true
        }
    }

    /**
     * Complete-only publication gate, not a downloader. The producer must validate format contents and
     * publish an immutable file under books before calling this method. Opening here checks the private
     * path, nonempty bytes and any known length. Failed publication leaves the old manifest intact.
     * Query/open and replacement share copyAccess; the factory retires old generations after live handles close.
     * A supplied task ID also requires the current binding/control gate and commits terminal state atomically.
     */
    suspend fun publishComplete(copy: DownloadedCopy, taskId: UUID? = null, stamp: CalibreStamp? = null): Boolean = withContext(ioDispatcher) { copyAccess.withLock {
        val previous = find(copy.key)
        val published = transaction {
            val identity = requireNotNull(binding(this, copy.key.book.libraryId))
            require(identity.location.backend == copy.savedVersion.backend)
            if (taskId != null) {
                if (current(this)?.identity != identity) return@transaction false
                val allowed = rawQuery("SELECT control FROM queued_tasks WHERE task_id = ? AND revoked = 0", arrayOf(taskId.toString())).use {
                    it.moveToFirst() && it.isNull(0)
                }
                if (!allowed) return@transaction false
            }
            val opened = files.open(copy.location, copy.sizeBytes)
            require(opened is HandleOpenResult.Opened) { "Complete private file is required" }
            opened.handle.use { }
            val values = copyValues(copy).apply { putStamp(stamp) }
            val args = keyArgs(copy.key)
            if (update("downloaded_copies", values, KEY_WHERE, args) == 0) insertOrThrow("downloaded_copies", null, values)
            if (taskId != null) DurableTaskQueue.completePublication(this, taskId)
            true
        }
        if (published && previous != null && previous.location != copy.location) files.retire(previous.location)
        published
    } }

    /** Removes crash-orphan generations while serializing query/open and preserving live handles. */
    suspend fun collectUnreferenced(libraryId: LibraryId) = withContext(ioDispatcher) { copyAccess.withLock {
        val retained = mutableSetOf<CompleteCopyLocation>()
        var offset = 0
        while (true) {
            val page = listCopies(libraryId, 200, offset)
            retained.addAll(page.map { it.location })
            if (page.size < 200) break
            offset += page.size
        }
        files.collectUnreferenced(libraryId, retained)
    } }

    /** Retire only the generations frozen by scoped cleanup, preserving unrelated pending publication. */
    internal suspend fun retireCopies(locations: Set<CompleteCopyLocation>) = withContext(ioDispatcher) { copyAccess.withLock {
        locations.forEach { files.retire(it) }
    } }

    /**
     * Only an explicit source check changes this field; transport/auth failures never call it.
     * A [stamp] records the imported Calibre record that an unchanged source version was confirmed against.
     */
    suspend fun confirmSource(key: CopyKey, availability: SourceAvailability, stamp: CalibreStamp? = null) = withContext(ioDispatcher) {
        require(availability != SourceAvailability.UNCONFIRMED)
        database.writableDatabase.update("downloaded_copies", ContentValues().apply {
            put("source_availability", if (availability == SourceAvailability.AVAILABLE) "available" else "missing")
            if (stamp != null) putStamp(stamp)
        }, KEY_WHERE, keyArgs(key))
    }

    private fun ContentValues.putStamp(stamp: CalibreStamp?) {
        put("calibre_recorded", if (stamp == null) 0 else 1)
        if (stamp?.modified == null) putNull("calibre_modified") else put("calibre_modified", stamp.modified)
        if (stamp?.sizeBytes == null) putNull("calibre_size") else put("calibre_size", stamp.sizeBytes)
    }

    override suspend fun find(key: CopyKey): DownloadedCopy? = withContext(ioDispatcher) {
        try {
            database.readableDatabase.query("downloaded_copies", null, KEY_WHERE, keyArgs(key), null, null, null).use {
                if (it.moveToFirst()) it.copy() else null
            }
        } catch (_: SQLiteException) {
            // Preserve PrivateCopyReader's structured LOCAL_IO result without exposing SQL or source tokens.
            throw IOException("Application manifest query failed")
        }
    }

    /** The complete record that currently owns [location]; null once that generation is replaced or removed. */
    suspend fun findAt(location: CompleteCopyLocation): DownloadedCopy? = withContext(ioDispatcher) {
        database.readableDatabase.query("downloaded_copies", null, "library_id = ? AND file_generation = ?",
            arrayOf(location.libraryId.value.toString(), location.fileGeneration.toString()), null, null, null).use {
            if (it.moveToFirst()) it.copy() else null
        }
    }

    /** Bounded local manifest pagination, independent of the full metadata index and source access. */
    suspend fun listCopies(libraryId: LibraryId, limit: Int, offset: Int): List<DownloadedCopy> = withContext(ioDispatcher) {
        require(limit in 1..200 && offset >= 0)
        database.readableDatabase.query(
            "downloaded_copies", null, "library_id = ?", arrayOf(libraryId.value.toString()), null, null,
            "source_id, source_uuid, format", "$offset,$limit",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.copy()) } }
    }

    suspend fun countCopies(libraryId: LibraryId): Int = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM downloaded_copies WHERE library_id = ?",
            arrayOf(libraryId.value.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    internal fun selectInTransaction(db: SQLiteDatabase, location: LibraryLocation): LibrarySelection {
        val previous = cachedIdentity(db, location)
        val selection = LibrarySelection(UUID.randomUUID(), location, previous)
        val values = locationValues(location).apply {
            put("singleton", 1)
            put("token", selection.token.toString())
            if (previous == null) putNull("library_id") else put("library_id", previous.id.value.toString())
            putNull("authorization_id")
        }
        if (db.update("current_selection", values, "singleton = 1", null) == 0) db.insertOrThrow("current_selection", null, values)
        return selection
    }

    /** Re-selecting a known position restores its last valid private import without source access. */
    private fun cachedIdentity(db: SQLiteDatabase, location: LibraryLocation): LibraryIdentity? {
        return db.rawQuery("""
            SELECT b.library_id FROM library_bindings b JOIN library_preferences m ON b.library_id = m.library_id
            WHERE b.backend = ? AND b.location_key = ?
            ORDER BY m.last_imported_at DESC, m.rowid DESC LIMIT 1
        """.trimIndent(), locationArgs(location)).use {
            if (it.moveToFirst()) binding(db, LibraryId(UUID.fromString(it.getString(0)))) else null
        }
    }

    internal fun current(db: SQLiteDatabase): LibrarySelection? = db.query("current_selection", null, "singleton = 1", null, null, null, null).use {
        if (!it.moveToFirst()) null else LibrarySelection(
            UUID.fromString(it.text("token")), if (it.optionalText("location_key") == null) null else it.location(),
            it.optionalText("library_id")?.let { id -> binding(db, LibraryId(UUID.fromString(id))) },
            LocationKeys.backend(it.text("backend")),
            it.optionalText("authorization_id")?.let(UUID::fromString),
        )
    }

    internal fun binding(db: SQLiteDatabase, id: LibraryId): LibraryIdentity? = db.query(
        "library_bindings", null, "library_id = ?", arrayOf(id.value.toString()), null, null, null,
    ).use { if (!it.moveToFirst()) null else LibraryIdentity(id, it.location(), UUID.fromString(it.text("generation"))) }

    private fun <T> transaction(action: SQLiteDatabase.() -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val result = db.action()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    internal fun locationValues(location: LibraryLocation) = ContentValues().apply {
        put("backend", LocationKeys.backendCode(location.backend))
        put("location_key", LocationKeys.encode(location))
    }

    /** Arguments for `backend = ? AND location_key = ?`. */
    internal fun locationArgs(location: LibraryLocation) =
        arrayOf(LocationKeys.backendCode(location.backend), LocationKeys.encode(location))

    private fun Cursor.location(): LibraryLocation = LocationKeys.decode(LocationKeys.backend(text("backend")), text("location_key"))

    private fun copyValues(copy: DownloadedCopy) = ContentValues().apply {
        put("library_id", copy.key.book.libraryId.value.toString())
        put("source_id", copy.key.book.sourceId)
        put("source_uuid", copy.key.book.sourceUuid.toString())
        put("format", copy.key.format.value)
        put("file_generation", copy.location.fileGeneration.toString())
        put("title", copy.title)
        if (copy.sizeBytes == null) putNull("size_bytes") else put("size_bytes", copy.sizeBytes)
        put("version_backend", LocationKeys.backendCode(copy.savedVersion.backend))
        put("version_token", copy.savedVersion.token)
        put("source_availability", when (copy.sourceAvailability) {
            SourceAvailability.UNCONFIRMED -> "unconfirmed"
            SourceAvailability.AVAILABLE -> "available"
            SourceAvailability.CONFIRMED_MISSING -> "missing"
        })
    }

    private fun Cursor.copy(): DownloadedCopy {
        val library = LibraryId(UUID.fromString(text("library_id")))
        return DownloadedCopy(
            CopyKey(BookKey(library, getLong(getColumnIndexOrThrow("source_id")), UUID.fromString(text("source_uuid"))), BookFormat.parse(text("format"))),
            CompleteCopyLocation(library, UUID.fromString(text("file_generation"))), text("title"),
            getColumnIndexOrThrow("size_bytes").let { if (isNull(it)) null else getLong(it) },
            FileVersion(LocationKeys.backend(text("version_backend")), text("version_token")),
            when (text("source_availability")) {
                "unconfirmed" -> SourceAvailability.UNCONFIRMED
                "available" -> SourceAvailability.AVAILABLE
                "missing" -> SourceAvailability.CONFIRMED_MISSING
                else -> error("Unknown source availability")
            },
        )
    }

    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.optionalText(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun keyArgs(key: CopyKey) = arrayOf(key.book.libraryId.value.toString(), key.book.sourceId.toString(), key.book.sourceUuid.toString(), key.format.value)

    companion object {
        private const val KEY_WHERE = "library_id = ? AND source_id = ? AND source_uuid = ? AND format = ?"
    }
}
