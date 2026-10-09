package io.github.chenxiex.calibrecloud.storage.local

import android.system.ErrnoException
import android.system.OsConstants

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.model.SourceFileLocator
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Provider metadata is a locator/capability/size hint, never content-version or integrity evidence. */
data class LocalDocument(val id: String, val name: String, val directory: Boolean, val writable: Boolean, val sizeBytes: Long? = null)

/** Read-only source surface. Implementations must verify the actual grant and selected local provider. */
interface LocalDocumentAccess {
    fun root(treeUri: String): LocalDocument
    fun children(treeUri: String, parentId: String): List<LocalDocument>
    /** Whether [locate] finds a root-relative path with one lookup instead of listing each directory. */
    val locatesByPath: Boolean get() = false
    /** The document at root-relative [path], or null when it does not exist; used only when [locatesByPath]. */
    fun locate(treeUri: String, path: RelativeSourcePath): LocalDocument? = throw UnsupportedOperationException()
    fun isWithinRoot(treeUri: String, documentId: String): Boolean
    fun openRead(treeUri: String, documentId: String): InputStream
    /** Must seek directly, without reading/skipping the prefix. null for non-seekable providers. */
    fun openRange(treeUri: String, documentId: String, offset: Long): InputStream? = null
}

class LocalSourceException(val kind: StorageErrorKind) : IOException()

sealed interface LocalSourceResult<out T> {
    data class Available<T>(val value: T) : LocalSourceResult<T>
    data class Failed(val error: StorageError) : LocalSourceResult<Nothing>
}

data class LocalSourceFile(val locator: SourceFileLocator.Local, val writable: Boolean)
data class LocalDatabaseSnapshot(val file: File, val version: FileVersion)

typealias LocalSnapshotResult = LocalSourceResult<LocalDatabaseSnapshot>

/** Checks only an application-owned candidate, without migration, repair or source access. */
fun interface SnapshotValidator {
    fun validate(file: File): Boolean
}

/**
 * Explicit source access only; callers schedule operations through the shared task coordinator.
 * A path is located directly when the provider supports it, otherwise resolved one component at a
 * time; every returned ID is confined to the grant.
 * SHA-256 of all bytes supplies the version even when provider size/time metadata is absent.
 * Snapshot acquisition rejects any nonempty WAL, rollback journal or unknown-length log, checks
 * logs before/after both reads, and compares the copy hash with a second complete source read.
 * These observations detect changes; they do not claim arbitrary concurrent writers are safe.
 * Published files are immutable private generations. Failures only delete this attempt's staging
 * file and cannot replace or remove an earlier valid snapshot. Calibre structure import belongs to the metadata module.
 */
class LocalSourceBackend(
    private val documents: LocalDocumentAccess,
    private val snapshotsDirectory: File,
    private val validator: SnapshotValidator,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun resolve(treeUri: String, path: RelativeSourcePath): LocalSourceResult<LocalSourceFile> = operation {
        val document = resolveDocument(treeUri, path)
        LocalSourceFile(SourceFileLocator.Local(document.id), document.writable)
    }

    /** Provider-reported size for progress and space pre-checks only; null when the provider omits it. */
    suspend fun size(treeUri: String, path: RelativeSourcePath): LocalSourceResult<Long?> = operation {
        resolveDocument(treeUri, path).sizeBytes?.takeIf { it > 0 }
    }

    /** The returned stream is owned by the caller; subsequent reads must remain off the UI thread. */
    suspend fun openRead(treeUri: String, path: RelativeSourcePath): LocalSourceResult<InputStream> = operation {
        openSource(treeUri, resolveDocument(treeUri, path).id)
    }

    /** The executor verifies the complete content version before and after this positioned read. */
    suspend fun openRange(treeUri: String, path: RelativeSourcePath, offset: Long, expectedVersion: FileVersion): LocalSourceResult<InputStream?> = operation {
        require(offset > 0 && expectedVersion.backend == BackendKind.LOCAL)
        val document = resolveDocument(treeUri, path)
        try {
            documents.openRange(treeUri, document.id, offset)
        } catch (_: java.io.FileNotFoundException) {
            throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
        }
    }

    suspend fun version(treeUri: String, path: RelativeSourcePath, checkControl: suspend () -> Unit = {}): LocalSourceResult<FileVersion> = operation {
        val document = resolveDocument(treeUri, path)
        fileVersion(hash(openSource(treeUri, document.id), checkControl))
    }

    /**
     * Reads a small file once into memory; the version is the SHA-256 of exactly the returned bytes.
     * Files larger than [maxBytes] fail as corrupt content without reading further.
     */
    suspend fun readSmall(treeUri: String, path: RelativeSourcePath, maxBytes: Int, checkControl: suspend () -> Unit = {}): LocalSourceResult<Pair<ByteArray, FileVersion>> = operation {
        val document = resolveDocument(treeUri, path)
        openSource(treeUri, document.id).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                checkControl()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw LocalSourceException(StorageErrorKind.CORRUPT_CONTENT)
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
            output.toByteArray() to fileVersion(digest.digest())
        }
    }

    suspend fun acquireSnapshot(
        treeUri: String,
        candidateId: UUID,
        checkControl: suspend () -> Unit = {},
    ): LocalSnapshotResult = operation {
        val directory = File(snapshotsDirectory, candidateId.toString())
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
        val attemptId = UUID.randomUUID().toString()
        val staging = File(directory, "$attemptId.part")
        val published = File(directory, "$attemptId.db")
        try {
            checkControl()
            checkLogs(treeUri)
            val source = resolveDocument(treeUri, RelativeSourcePath("metadata.db"))
            val firstHash = openSource(treeUri, source.id).use { input ->
                staging.outputStream().use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkControl()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                    digest.digest()
                }
            }
            checkControl()
            checkLogs(treeUri)
            val refreshed = resolveDocument(treeUri, RelativeSourcePath("metadata.db"))
            if (refreshed.id != source.id) throw LocalSourceException(StorageErrorKind.VERSION_CONFLICT)
            val secondHash = hash(openSource(treeUri, refreshed.id), checkControl)
            checkControl()
            checkLogs(treeUri)
            if (!firstHash.contentEquals(secondHash)) throw LocalSourceException(StorageErrorKind.VERSION_CONFLICT)
            if (!validator.validate(staging)) throw LocalSourceException(StorageErrorKind.CORRUPT_CONTENT)
            currentCoroutineContext().ensureActive()
            checkControl()
            if (!staging.renameTo(published)) throw IOException()
            LocalDatabaseSnapshot(published, fileVersion(firstHash))
        } finally {
            staging.delete()
        }
    }

    private fun resolveDocument(treeUri: String, path: RelativeSourcePath): LocalDocument {
        if (documents.locatesByPath) {
            val document = documents.locate(treeUri, path) ?: throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
            if (!documents.isWithinRoot(treeUri, document.id)) throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
            if (document.directory) throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
            return document
        }
        var document = documents.root(treeUri)
        if (!document.directory || !documents.isWithinRoot(treeUri, document.id)) {
            throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
        }
        val segments = path.value.split('/')
        segments.forEachIndexed { index, name ->
            val matches = documents.children(treeUri, document.id).filter { it.name == name }
            if (matches.isEmpty()) throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
            if (matches.size != 1) throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
            document = matches.single()
            if (!documents.isWithinRoot(treeUri, document.id)) throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
            if (index < segments.lastIndex && !document.directory) {
                throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
            }
        }
        if (document.directory) throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
        return document
    }

    private suspend fun checkLogs(treeUri: String) {
        val root = documents.root(treeUri)
        if (!root.directory || !documents.isWithinRoot(treeUri, root.id)) {
            throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
        }
        val logs = documents.children(treeUri, root.id).filter {
            it.name in setOf("metadata.db-wal", "metadata.db-journal", "metadata.db-shm") ||
                it.name.startsWith("metadata.db-mj")
        }
        for (log in logs) {
            currentCoroutineContext().ensureActive()
            if (!documents.isWithinRoot(treeUri, log.id) || log.directory) {
                throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
            }
            // SHM can exist for a live writer even with an empty WAL: conservatively refuse it.
            if (log.name == "metadata.db-shm" || openSource(treeUri, log.id).use { it.read() != -1 }) {
                throw LocalSourceException(StorageErrorKind.VERSION_CONFLICT)
            }
        }
    }

    private suspend fun hash(input: InputStream, checkControl: suspend () -> Unit = {}): ByteArray = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            checkControl()
            val count = it.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private fun openSource(treeUri: String, documentId: String): InputStream = try {
        documents.openRead(treeUri, documentId)
    } catch (_: java.io.FileNotFoundException) {
        throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
    }

    private fun fileVersion(hash: ByteArray) = FileVersion(BackendKind.LOCAL, hash.joinToString("") { "%02x".format(it) })

    private suspend fun <T> operation(block: suspend () -> T): LocalSourceResult<T> = withContext(ioDispatcher) {
        try {
            LocalSourceResult.Available(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LocalSourceException) {
            LocalSourceResult.Failed(StorageError(error.kind))
        } catch (_: SecurityException) {
            LocalSourceResult.Failed(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED))
        } catch (error: IOException) {
            val noSpace = generateSequence<Throwable>(error) { it.cause }.any {
                it is ErrnoException && it.errno == OsConstants.ENOSPC
            }
            LocalSourceResult.Failed(StorageError(if (noSpace) StorageErrorKind.INSUFFICIENT_SPACE else StorageErrorKind.LOCAL_IO))
        } catch (_: UnsupportedOperationException) {
            LocalSourceResult.Failed(StorageError(StorageErrorKind.UNSUPPORTED_OPERATION))
        }
    }
}
