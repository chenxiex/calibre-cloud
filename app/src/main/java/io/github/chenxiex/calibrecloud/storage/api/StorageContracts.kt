package io.github.chenxiex.calibrecloud.storage.api

import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryId
import java.io.Closeable
import java.io.InputStream
import java.util.UUID

/** Safe diagnostic correlation token; arbitrary exception text and URLs cannot be carried here. */
data class DiagnosticId(val value: UUID)

/**
 * THROTTLED covers server throttling and temporary server errors that ask the client to retry later.
 * LEFTOVER_FILES: a read status commit found files under its temporary names that it cannot prove it
 * created, and left them for the user to handle.
 */
enum class StorageErrorKind {
    NO_NETWORK, THROTTLED, LOGIN_REQUIRED, AUTHORIZATION_EXPIRED, SOURCE_MISSING,
    INCOMPATIBLE_DATABASE, VERSION_CONFLICT, INSUFFICIENT_SPACE,
    CORRUPT_CONTENT, UNSUPPORTED_OPERATION, LOCAL_IO, LEFTOVER_FILES,
}

data class StorageError(val kind: StorageErrorKind, val diagnosticId: DiagnosticId? = null)

enum class SourceAvailability { UNCONFIRMED, AVAILABLE, CONFIRMED_MISSING }

/** Internal immutable file generation, relative to filesDir/books, never an arbitrary path. */
data class CompleteCopyLocation(val libraryId: LibraryId, val fileGeneration: UUID)

/** Only validated, atomically published complete copies enter this query contract. */
data class DownloadedCopy(
    val key: CopyKey,
    val location: CompleteCopyLocation,
    val title: String,
    val sizeBytes: Long?,
    val savedVersion: FileVersion,
    val sourceAvailability: SourceAvailability,
) {
    init {
        require(location.libraryId == key.book.libraryId)
        require(sizeBytes == null || sizeBytes > 0)
    }
}

/**
 * The imported Calibre record a downloaded copy was last published or confirmed against:
 * books.last_modified and the format size. OneDrive re-checks a copy only when these change.
 */
data class CalibreStamp(val modified: String?, val sizeBytes: Long?)

fun interface CompleteCopyQuery {
    suspend fun find(key: CopyKey): DownloadedCopy?
}

/** Owns an already-open application copy. Caller closes it; subsequent stream I/O stays off the UI thread. */
interface ApplicationCopyHandle : Closeable {
    val input: InputStream
    val sizeBytes: Long
}

sealed interface CopyReadResult {
    data class Available(val handle: ApplicationCopyHandle, val version: FileVersion) : CopyReadResult
    data object Missing : CopyReadResult
    data class Failed(val error: StorageError) : CopyReadResult
}

/** Local copies only: no source fallback, queue waiting, download flag or network dependency. */
fun interface CopyReader {
    suspend fun read(key: CopyKey): CopyReadResult
}

sealed interface StorageOperationResult {
    data object Completed : StorageOperationResult
    data class Failed(val error: StorageError) : StorageOperationResult
}

/** Removing an exact copy cannot mean deleting its source or another format. */
interface CopyMaintenance {
    suspend fun removeCopy(key: CopyKey): StorageOperationResult
    suspend fun clearMetadata(libraryId: LibraryId): StorageOperationResult
}
