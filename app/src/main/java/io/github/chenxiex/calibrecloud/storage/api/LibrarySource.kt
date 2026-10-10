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
 * Why read status cannot be written back to a library (R15); shown where marking is disabled.
 * AUTHORIZATION_REQUIRED: the library must be granted again; READ_ONLY_GRANT: the grant lacks write
 * access; UNSUPPORTED_PROVIDER: the source cannot guarantee every operation the commit needs;
 * SOURCE_UNAVAILABLE: metadata.db cannot be reached now; NOT_IMPLEMENTED: the backend has no commit yet.
 */
enum class WriteBlock { AUTHORIZATION_REQUIRED, READ_ONLY_GRANT, UNSUPPORTED_PROVIDER, SOURCE_UNAVAILABLE, NOT_IMPLEMENTED }

/** Result of a push whose version precondition was checked by the source. */
sealed interface PushOutcome {
    /** The source now holds the pushed database at [version]. */
    data class Pushed(val version: FileVersion) : PushOutcome
    /** The source no longer had the base version; nothing was replaced (R15). */
    data object Conflict : PushOutcome
}

/**
 * Private, durable record of one write task's push, kept across rounds and process death until the
 * backend clears it. Only the backend interprets [read]'s value; [write] returns once it is on disk.
 */
interface PushJournal {
    suspend fun read(): String?
    /** null removes the record. */
    suspend fun write(value: String?)
}

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

    /**
     * How many source files one task may read at once, such as the covers of a page batch (R10). The
     * queue still runs one task at a time, so this bounds all reads of the backend.
     */
    val parallelReads: Int

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

    /** Why read status cannot be written to [location] now, or null when it can; no network request. */
    suspend fun writeCapability(location: LibraryLocation): WriteBlock?

    /**
     * Replaces metadata.db with [staged] (lower-case hex SHA-256 [stagedSha256]) only while the source
     * still has [base], the version the staged database was built from (R15). A backend that commits in
     * several steps records its progress in [journal] before each one. Neither pausable nor cancellable.
     * A failure whose result is unknown is thrown as a transient [SourceFailure]; the caller then runs a
     * whole round again.
     */
    suspend fun pushDatabase(location: LibraryLocation, staged: File, stagedSha256: String, base: FileVersion,
        journal: PushJournal): PushOutcome

    /**
     * Completes or rolls back a push that [journal] shows was interrupted, so the next round starts from
     * a whole metadata.db, then clears the journal. Does nothing when there is none.
     */
    suspend fun finishPendingPush(location: LibraryLocation, journal: PushJournal)
}

fun interface LibrarySources {
    fun of(backend: BackendKind): LibrarySource
}

fun LibrarySources.of(location: LibraryLocation): LibrarySource = of(location.backend)
