package io.github.chenxiex.calibrecloud.tasks.copies

import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.local.*
import io.github.chenxiex.calibrecloud.storage.onedrive.*
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class FormatSourceFailure(val error: StorageError, val transient: Boolean = false, val retryDelayMillis: Long? = null) : IOException()

/** Only the serial task executor invokes this interface. Streams belong to the caller. */
interface FormatSource {
    suspend fun version(location: LibraryLocation, path: RelativeSourcePath): FileVersion
    suspend fun versionChecked(location: LibraryLocation, path: RelativeSourcePath, control: suspend () -> Unit): FileVersion {
        control()
        val version = version(location, path)
        control()
        return version
    }
    /** Exact size that the transfer must match; null when integrity relies on the content hash instead. */
    suspend fun size(location: LibraryLocation, path: RelativeSourcePath): Long? = null
    /** Size for progress and space pre-checks only, never integrity evidence. */
    suspend fun estimatedSize(location: LibraryLocation, path: RelativeSourcePath): Long? = size(location, path)
    suspend fun open(location: LibraryLocation, path: RelativeSourcePath): InputStream
    /** null means range access is unavailable; callers must discard the prefix and restart. */
    suspend fun openRange(location: LibraryLocation, path: RelativeSourcePath, offset: Long, expectedVersion: FileVersion): InputStream? = null
}

class BackendFormatSource(
    private val state: ApplicationStateRepository,
    private val local: LocalSourceBackend,
    private val oneDrive: OneDriveSourceBackend,
) : FormatSource {
    private suspend fun tree(location: LibraryLocation.Local): String {
        val current = state.current()
        if (current?.location != location) throw FormatSourceFailure(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED))
        return state.localTreeUri() ?: throw FormatSourceFailure(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED))
    }
    override suspend fun version(location: LibraryLocation, path: RelativeSourcePath): FileVersion = when (location) {
        is LibraryLocation.Local -> local.version(tree(location), path).value()
        is LibraryLocation.OneDrive -> oneDrive.version(location, path).value()
    }
    override suspend fun versionChecked(location: LibraryLocation, path: RelativeSourcePath, control: suspend () -> Unit): FileVersion = when (location) {
        is LibraryLocation.Local -> local.version(tree(location), path, control).value()
        is LibraryLocation.OneDrive -> super.versionChecked(location, path, control)
    }
    override suspend fun size(location: LibraryLocation, path: RelativeSourcePath): Long? = when (location) {
        is LibraryLocation.Local -> null // The local content hash validates every transferred byte.
        is LibraryLocation.OneDrive -> oneDrive.resolve(location, path).value().sizeBytes
    }
    override suspend fun estimatedSize(location: LibraryLocation, path: RelativeSourcePath): Long? = when (location) {
        is LibraryLocation.Local -> local.size(tree(location), path).value()
        is LibraryLocation.OneDrive -> size(location, path)
    }
    override suspend fun open(location: LibraryLocation, path: RelativeSourcePath): InputStream = when (location) {
        is LibraryLocation.Local -> local.openRead(tree(location), path).value()
        is LibraryLocation.OneDrive -> oneDrive.openRead(location, path).value().networkFailures()
    }
    override suspend fun openRange(location: LibraryLocation, path: RelativeSourcePath, offset: Long, expectedVersion: FileVersion): InputStream? = when (location) {
        is LibraryLocation.Local -> local.openRange(tree(location), path, offset, expectedVersion).value()
        is LibraryLocation.OneDrive -> oneDrive.openRange(location, path, offset, expectedVersion).value()?.networkFailures()
    }
    private fun <T> LocalSourceResult<T>.value(): T = when (this) {
        is LocalSourceResult.Available -> value
        is LocalSourceResult.Failed -> throw FormatSourceFailure(error)
    }
    private fun <T> OneDriveSourceResult<T>.value(): T = when (this) {
        is OneDriveSourceResult.Available -> value
        is OneDriveSourceResult.Failed -> throw FormatSourceFailure(error, transient, retryDelayMillis)
    }
}

/** Converts only source stream I/O, never the executor's private output writes. */
internal fun InputStream.networkFailures(): InputStream = object : FilterInputStream(this) {
    override fun read(): Int = sourceRead { `in`.read() }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = sourceRead { `in`.read(buffer, offset, length) }
    override fun skip(count: Long): Long = sourceRead { `in`.skip(count) }
    override fun available(): Int = sourceRead { `in`.available() }
    override fun close() = sourceRead { `in`.close() }

    private inline fun <T> sourceRead(block: () -> T): T = try {
        block()
    } catch (failure: FormatSourceFailure) {
        throw failure
    } catch (failure: OneDriveSourceException) {
        throw FormatSourceFailure(StorageError(failure.kind), failure.transient, failure.retryDelayMillis)
    } catch (_: IOException) {
        throw FormatSourceFailure(StorageError(StorageErrorKind.NO_NETWORK), true)
    }
}
