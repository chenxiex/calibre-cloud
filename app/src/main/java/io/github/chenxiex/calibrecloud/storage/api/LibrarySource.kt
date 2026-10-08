package io.github.chenxiex.calibrecloud.storage.api

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Classified failure of a source operation or of a stream it returned. A [transient] failure is retried
 * by the scheduler, no earlier than [retryDelayMillis] when the backend reports one; a THROTTLED failure
 * with a delay holds every source task of the same library until then (R08). Carries no URL, credential
 * or path.
 */
class SourceFailure(val error: StorageError, val transient: Boolean = false, val retryDelayMillis: Long? = null) : IOException() {
    constructor(kind: StorageErrorKind) : this(StorageError(kind))
}

/** One observation of a source file. Streams opened from it belong to the caller. */
interface SourceFile {
    val version: FileVersion
    /** Exact size that the transfer must match; null when integrity relies on [contentSha256] instead. */
    val size: Long?
    /** Size for progress and space pre-checks only, never integrity evidence. */
    val estimatedSize: Long?
    /** Lower-case hex SHA-256 the complete content must have; null when the backend has no such digest. */
    val contentSha256: String?
    suspend fun open(): InputStream
    /** null means range access is unavailable; callers must discard the prefix and restart. */
    suspend fun openRange(offset: Long): InputStream?
}

/** An opened image and the version of the source file it belongs to. The caller closes [input]. */
class SourceStream(val input: InputStream, val version: FileVersion)

/** A private, validated SQLite copy of metadata.db and the source version it was read at. */
class SourceSnapshot(val file: File, val version: FileVersion)

/** The user action that lets work blocked by an authorization failure continue. */
enum class Reauthorization { SIGN_IN, DIRECTORY_GRANT }

/**
 * Access to the activated libraries of one backend. Every backend implements the whole contract;
 * callers select an implementation by [LibraryLocation.backend] through [LibrarySources] and never
 * branch on the backend. Request-saving rules stay inside the operations; behavior that the
 * requirements define per backend is declared by the properties below rather than by callers.
 *
 * Only the serial task executor invokes the operations. [control] is called at safe boundaries and
 * may throw to pause or cancel. Failures are thrown as [SourceFailure].
 */
interface LibrarySource {
    val backend: BackendKind

    /** Whether operations need a network connection; queued work waits for one when it is absent. */
    val requiresNetwork: Boolean

    /**
     * Whether a recorded path that is not found first triggers one metadata sync and one retry at the
     * newly imported path before the file counts as missing (R11). false: missing at once.
     */
    val resyncsMissingPath: Boolean

    /** Whether work that failed with [kind] waits for the user action instead of failing (R18). */
    fun reauthorization(kind: StorageErrorKind): Reauthorization?

    /**
     * Whether a sync checks the version of a downloaded copy whose Calibre record was [recorded] when it
     * was downloaded or last confirmed, and is [imported] now (R11). [recorded] is null when not yet
     * recorded; [imported] is null when the book or format is gone.
     */
    fun checksCopy(recorded: CalibreStamp?, imported: CalibreStamp?): Boolean

    suspend fun lookup(location: LibraryLocation, path: RelativeSourcePath, control: suspend () -> Unit): SourceFile

    /** Whether the file at [path] still has [version] after a read, checked once before publication. */
    suspend fun unchanged(location: LibraryLocation, path: RelativeSourcePath, version: FileVersion, control: suspend () -> Unit): Boolean

    /** The cover image at [path], at least the target size when the backend can scale it, otherwise the original. */
    suspend fun openCover(location: LibraryLocation, path: RelativeSourcePath, targetWidth: Int, targetHeight: Int,
        control: suspend () -> Unit): SourceStream

    /**
     * A consistent private copy of metadata.db, or null when the source still has [unchangedVersion]
     * and nothing was read. A backend that cannot tell without reading always returns a copy.
     * [candidateId] scopes the private files of this attempt.
     */
    suspend fun acquireSnapshot(location: LibraryLocation, candidateId: UUID, unchangedVersion: FileVersion?,
        control: suspend () -> Unit): SourceSnapshot?
}

fun interface LibrarySources {
    fun of(backend: BackendKind): LibrarySource
}

fun LibrarySources.of(location: LibraryLocation): LibrarySource = of(location.backend)
