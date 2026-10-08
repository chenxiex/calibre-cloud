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

    /** Starts explicit account/directory discovery before a stable root exists; no fabricated identity. */
    suspend fun beginCandidate(backend: BackendKind, authorizationId: UUID): CandidateContext = withContext(ioDispatcher) {
        transaction {
            val token = UUID.randomUUID()
            val values = ContentValues().apply {
                put("singleton", 1); put("token", token.toString()); put("backend", backendCode(backend))
                put("authority", ""); put("root_id", ""); put("account_id", ""); put("drive_id", "")
                putNull("library_id"); put("authorization_id", authorizationId.toString())
            }
            if (update("current_selection", values, "singleton = 1", null) == 0) insertOrThrow("current_selection", null, values)
            CandidateContext(token, backend, authorizationId)
        }
    }

    /** Compare-and-publish the discovered stable location in the same selection transaction. */
    suspend fun resolveCandidate(context: CandidateContext, location: LibraryLocation): Boolean = withContext(ioDispatcher) {
        transaction {
            val selected = current(this) ?: return@transaction false
            if (selected.token != context.selectionToken || selected.backend != context.backend ||
                selected.authorizationId != context.authorizationId || location.backend != context.backend) return@transaction false
            if (selected.location != null && selected.location != location) return@transaction false
            update("current_selection", locationValues(location), "singleton = 1", null)
            true
        }
    }

    /** Explicit directory selection rejects an obsolete browser context in the selection transaction. */
    suspend fun chooseCandidate(context: CandidateContext, location: LibraryLocation): CandidateContext? = withContext(ioDispatcher) {
        transaction {
            val selected = current(this) ?: return@transaction null
            if (selected.token != context.selectionToken || selected.backend != context.backend ||
                selected.authorizationId != context.authorizationId || location.backend != context.backend) return@transaction null
            val next = CandidateContext(UUID.randomUUID(), context.backend, context.authorizationId)
            val cached = cachedIdentity(this, location)
            update("current_selection", locationValues(location).apply {
                put("token", next.selectionToken.toString())
                if (cached == null) putNull("library_id") else put("library_id", cached.id.value.toString())
                put("authorization_id", context.authorizationId.toString())
            }, "singleton = 1", null)
            next
        }
    }

    /** Reauthorizing a saved position cannot overwrite a concurrent backend/directory selection. */
    suspend fun reauthorizeCandidate(selectionToken: UUID, authorizationId: UUID): CandidateContext? = withContext(ioDispatcher) {
        transaction {
            val selected = current(this) ?: return@transaction null
            if (selected.token != selectionToken || selected.backend != BackendKind.ONEDRIVE || selected.location == null) return@transaction null
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

    suspend fun binding(id: LibraryId): LibraryIdentity? = withContext(ioDispatcher) {
        binding(database.readableDatabase, id)
    }

    /** Historical incarnations at this stable location; the importer must verify compatibility before reuse. */
    suspend fun bindingsAt(location: LibraryLocation): List<LibraryIdentity> = withContext(ioDispatcher) {
        val values = locationValues(location)
        database.readableDatabase.query(
            "library_bindings", null,
            "backend = ? AND authority = ? AND root_id = ? AND account_id = ? AND drive_id = ?",
            arrayOf("backend", "authority", "root_id", "account_id", "drive_id").map { values.getAsString(it) }.toTypedArray(),
            null, null, "library_id",
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
    suspend fun publishComplete(copy: DownloadedCopy, taskId: UUID? = null): Boolean = withContext(ioDispatcher) { copyAccess.withLock {
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
            val values = copyValues(copy)
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

    /** Only an explicit source check changes this field; transport/auth failures never call it. */
    suspend fun confirmSource(key: CopyKey, availability: SourceAvailability) = withContext(ioDispatcher) {
        require(availability != SourceAvailability.UNCONFIRMED)
        database.writableDatabase.update("downloaded_copies", ContentValues().apply {
            put("source_availability", if (availability == SourceAvailability.AVAILABLE) "available" else "missing")
        }, KEY_WHERE, keyArgs(key))
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

    // Called only by the local authorization adapter, itself confined to its I/O dispatcher.
    internal fun localTreeUri(): String? = database.readableDatabase.rawQuery("SELECT tree_uri FROM local_authorization WHERE singleton = 1", null).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    internal fun saveLocalSelection(treeUri: String, location: LibraryLocation.Local) {
        transaction {
            execSQL("INSERT INTO local_authorization(singleton, tree_uri) VALUES(1, ?) ON CONFLICT(singleton) DO UPDATE SET tree_uri = excluded.tree_uri", arrayOf(treeUri))
            selectInTransaction(this, location)
        }
    }

    /** One-time bridge from legacy local-directory preferences; never overwrites a newer current selection. */
    internal fun importLocalAuthorization(treeUri: String, location: LibraryLocation.Local) {
        transaction {
            if (localTreeUri() == null) {
                execSQL("INSERT INTO local_authorization(singleton, tree_uri) VALUES(1, ?)", arrayOf(treeUri))
                if (current(this) == null) selectInTransaction(this, location)
            }
        }
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
        val values = locationValues(location)
        return db.rawQuery("""
            SELECT b.library_id FROM library_bindings b JOIN library_preferences m ON b.library_id = m.library_id
            WHERE b.backend = ? AND b.authority = ? AND b.root_id = ? AND b.account_id = ? AND b.drive_id = ?
            ORDER BY m.last_imported_at DESC, m.rowid DESC LIMIT 1
        """.trimIndent(), arrayOf("backend", "authority", "root_id", "account_id", "drive_id")
            .map { values.getAsString(it) }.toTypedArray()).use {
            if (it.moveToFirst()) binding(db, LibraryId(UUID.fromString(it.getString(0)))) else null
        }
    }

    internal fun current(db: SQLiteDatabase): LibrarySelection? = db.query("current_selection", null, "singleton = 1", null, null, null, null).use {
        if (!it.moveToFirst()) null else LibrarySelection(
            UUID.fromString(it.text("token")), if (it.text("root_id").isEmpty()) null else it.location(),
            it.optionalText("library_id")?.let { id -> binding(db, LibraryId(UUID.fromString(id))) },
            when (it.text("backend")) { "local" -> BackendKind.LOCAL; "onedrive" -> BackendKind.ONEDRIVE; else -> error("Unknown backend") },
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
        put("backend", backendCode(location.backend))
        put("authority", (location as? LibraryLocation.Local)?.authority ?: "")
        put("root_id", when (location) {
            is LibraryLocation.Local -> location.treeDocumentId
            is LibraryLocation.OneDrive -> location.rootItemId
        })
        put("account_id", (location as? LibraryLocation.OneDrive)?.accountId ?: "")
        put("drive_id", (location as? LibraryLocation.OneDrive)?.driveId ?: "")
    }

    private fun Cursor.location(): LibraryLocation = when (text("backend")) {
        "local" -> LibraryLocation.Local(text("authority"), text("root_id"))
        "onedrive" -> LibraryLocation.OneDrive(text("account_id"), text("drive_id"), text("root_id"))
        else -> error("Unknown location backend")
    }

    private fun copyValues(copy: DownloadedCopy) = ContentValues().apply {
        put("library_id", copy.key.book.libraryId.value.toString())
        put("source_id", copy.key.book.sourceId)
        put("source_uuid", copy.key.book.sourceUuid.toString())
        put("format", copy.key.format.value)
        put("file_generation", copy.location.fileGeneration.toString())
        put("title", copy.title)
        if (copy.sizeBytes == null) putNull("size_bytes") else put("size_bytes", copy.sizeBytes)
        put("version_backend", backendCode(copy.savedVersion.backend))
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
            FileVersion(when (text("version_backend")) {
                "local" -> BackendKind.LOCAL
                "onedrive" -> BackendKind.ONEDRIVE
                else -> error("Unknown version backend")
            }, text("version_token")),
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
    private fun backendCode(backend: BackendKind) = when (backend) { BackendKind.LOCAL -> "local"; BackendKind.ONEDRIVE -> "onedrive" }
    private fun keyArgs(key: CopyKey) = arrayOf(key.book.libraryId.value.toString(), key.book.sourceId.toString(), key.book.sourceUuid.toString(), key.format.value)

    companion object {
        private const val KEY_WHERE = "library_id = ? AND source_id = ? AND source_uuid = ? AND format = ?"
    }
}
