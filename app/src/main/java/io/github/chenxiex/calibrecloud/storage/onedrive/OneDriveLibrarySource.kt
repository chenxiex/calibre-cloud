package io.github.chenxiex.calibrecloud.storage.onedrive

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.*
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * [LibrarySource] over [OneDriveSourceBackend] with the R08 request budget: one path request per
 * lookup supplies the version, exact size and download URL of every later read, and a read is not
 * re-checked before publication (staleness until the next sync is permitted). A sync checks only
 * copies whose Calibre record changed, a missing path first resyncs once, operations need a network,
 * and a lost sign-in waits for the user to sign in again. Read status is written back with one
 * conditional upload ([OneDriveSourceBackend.replaceDatabase]); nothing needs finishing afterwards.
 */
class OneDriveLibrarySource(private val source: OneDriveSourceBackend) : LibrarySource {
    override val backend = BackendKind.ONEDRIVE
    override val requiresNetwork = true
    override val resyncsMissingPath = true
    /** Kept low so a burst does not invite throttling, which then holds the whole library (R08). */
    override val parallelReads = 2
    override fun reauthorization(kind: StorageErrorKind) =
        if (kind == StorageErrorKind.LOGIN_REQUIRED) Reauthorization.SIGN_IN else null
    override fun checksCopy(recorded: CalibreStamp?, imported: CalibreStamp?) = recorded == null || recorded != imported

    override suspend fun lookup(location: LibraryLocation, path: RelativeSourcePath, control: suspend () -> Unit): SourceFile {
        val drive = drive(location)
        control()
        val file = source.lookup(drive, path).value()
        control()
        return object : SourceFile {
            override val version = file.version
            override val size = file.sizeBytes
            override val estimatedSize = file.sizeBytes
            override val contentSha256: String? = null
            override suspend fun open() = source.open(drive, file).value().networkFailures()
            override suspend fun openRange(offset: Long) = source.openRange(drive, file, offset).value()?.networkFailures()
        }
    }

    override suspend fun unchanged(location: LibraryLocation, path: RelativeSourcePath, version: FileVersion, control: suspend () -> Unit) = true

    /** One Graph request returns the image's cTag and thumbnails. */
    override suspend fun openCover(location: LibraryLocation, path: RelativeSourcePath, targetWidth: Int, targetHeight: Int,
        control: suspend () -> Unit): SourceStream {
        val drive = drive(location)
        control()
        val cover = source.openCover(drive, path, targetWidth, targetHeight).value()
        return SourceStream(cover.stream.networkFailures(), cover.version)
    }

    override suspend fun acquireSnapshot(location: LibraryLocation, candidateId: UUID, unchangedVersion: FileVersion?,
        control: suspend () -> Unit): SourceSnapshot? =
        source.acquireSnapshot(drive(location), candidateId, unchangedVersion, control).value()?.let { SourceSnapshot(it.file, it.version) }

    /**
     * Writable while the library's account is signed in, compared locally without a request. A sign-in
     * from before ID token subjects were stored cannot be compared and must sign in again.
     */
    override suspend fun writeCapability(location: LibraryLocation): WriteBlock? =
        if (location is LibraryLocation.OneDrive && source.signedInAccount() == location.accountId) null else WriteBlock.AUTHORIZATION_REQUIRED

    /** One upload either replaces metadata.db or nothing, so [journal] stays unused. */
    override suspend fun pushDatabase(location: LibraryLocation, staged: java.io.File, stagedSha256: String, base: FileVersion,
        journal: PushJournal): PushOutcome =
        source.replaceDatabase(drive(location), staged, stagedSha256, base).value()?.let { PushOutcome.Pushed(it) } ?: PushOutcome.Conflict

    override suspend fun finishPendingPush(location: LibraryLocation, journal: PushJournal) {}

    private fun drive(location: LibraryLocation) =
        location as? LibraryLocation.OneDrive ?: throw SourceFailure(StorageErrorKind.UNSUPPORTED_OPERATION)

    private fun <T> OneDriveSourceResult<T>.value(): T = when (this) {
        is OneDriveSourceResult.Available -> value
        is OneDriveSourceResult.Failed -> throw SourceFailure(error, transient, retryDelayMillis)
    }
}

/** Classifies failures of a content stream; the caller's own writes are never wrapped. */
internal fun InputStream.networkFailures(): InputStream = object : FilterInputStream(this) {
    override fun read(): Int = sourceRead { `in`.read() }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = sourceRead { `in`.read(buffer, offset, length) }
    override fun skip(count: Long): Long = sourceRead { `in`.skip(count) }
    override fun available(): Int = sourceRead { `in`.available() }
    override fun close() = sourceRead { `in`.close() }

    private inline fun <T> sourceRead(block: () -> T): T = try {
        block()
    } catch (failure: SourceFailure) {
        throw failure
    } catch (failure: OneDriveSourceException) {
        throw SourceFailure(StorageError(failure.kind), failure.transient, failure.retryDelayMillis)
    } catch (_: IOException) {
        throw SourceFailure(StorageError(StorageErrorKind.NO_NETWORK), true)
    }
}
