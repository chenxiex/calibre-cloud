package io.github.chenxiex.calibrecloud.storage.local

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import java.util.UUID

/**
 * [LibrarySource] over a granted SAF tree. The version is the SHA-256 of all bytes, so it is also the
 * content digest and is read again before publication. A cover is read once and hashed as it is read. Every downloaded copy is checked after each
 * import, a missing path is missing at once, nothing needs a network, and a lost grant waits for the
 * directory to be granted again. [treeUri] returns the grant the library at [LibraryLocation.Local] was
 * listed with, or null when it is not listed.
 */
class LocalLibrarySource(
    private val source: LocalSourceBackend,
    private val treeUri: suspend (LibraryLocation.Local) -> String?,
) : LibrarySource {
    override val backend = BackendKind.LOCAL
    override val requiresNetwork = false
    override val resyncsMissingPath = false
    /** Provider calls overlap well with decoding; more than three gains little on the target device. */
    override val parallelReads = 3
    override fun reauthorization(kind: StorageErrorKind) =
        if (kind == StorageErrorKind.AUTHORIZATION_EXPIRED) Reauthorization.DIRECTORY_GRANT else null
    override fun checksCopy(recorded: CalibreStamp?, imported: CalibreStamp?) = true

    override suspend fun lookup(location: LibraryLocation, path: RelativeSourcePath, control: suspend () -> Unit): SourceFile {
        val tree = tree(location)
        control()
        val observed = source.version(tree, path, control).value()
        control()
        val estimated = try { source.size(tree, path).value() } catch (_: SourceFailure) { null }
        return object : SourceFile {
            override val version = observed
            override val size: Long? = null
            override val estimatedSize = estimated
            override val contentSha256 = observed.token
            override suspend fun open() = source.openRead(tree, path).value()
            override suspend fun openRange(offset: Long) = source.openRange(tree, path, offset, observed).value()
        }
    }

    override suspend fun unchanged(location: LibraryLocation, path: RelativeSourcePath, version: FileVersion, control: suspend () -> Unit): Boolean {
        val tree = tree(location)
        control()
        return source.version(tree, path, control).value() == version
    }

    override suspend fun openCover(location: LibraryLocation, path: RelativeSourcePath, targetWidth: Int, targetHeight: Int,
        control: suspend () -> Unit): SourceStream {
        val tree = tree(location)
        val (bytes, version) = source.readSmall(tree, path, CoverRepository.MAX_ENCODED_BYTES.toInt(), control).value()
        control()
        return SourceStream(java.io.ByteArrayInputStream(bytes), version)
    }

    /** Always reads: the content hash that would prove an unchanged database needs the full read anyway. */
    override suspend fun acquireSnapshot(location: LibraryLocation, candidateId: UUID, unchangedVersion: FileVersion?,
        control: suspend () -> Unit): SourceSnapshot {
        val snapshot = source.acquireSnapshot(tree(location), candidateId, control).value()
        return SourceSnapshot(snapshot.file, snapshot.version)
    }

    private suspend fun tree(location: LibraryLocation): String =
        (location as? LibraryLocation.Local)?.let { treeUri(it) } ?: throw SourceFailure(StorageErrorKind.AUTHORIZATION_EXPIRED)

    private fun <T> LocalSourceResult<T>.value(): T = when (this) {
        is LocalSourceResult.Available -> value
        is LocalSourceResult.Failed -> throw SourceFailure(error)
    }
}
