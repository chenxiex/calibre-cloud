package io.github.chenxiex.calibrecloud.tasks.covers

import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackend
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceResult
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceBackend
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceResult
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.tasks.copies.FormatSourceFailure
import io.github.chenxiex.calibrecloud.tasks.copies.networkFailures
import java.io.InputStream

/** Only the serial task handler invokes this boundary, always with the imported cover.jpg path. */
fun interface CoverSource {
    suspend fun open(location: LibraryLocation, path: RelativeSourcePath): InputStream
}

class BackendCoverSource(
    private val state: ApplicationStateRepository,
    private val local: LocalSourceBackend,
    private val oneDrive: OneDriveSourceBackend,
) : CoverSource {
    override suspend fun open(location: LibraryLocation, path: RelativeSourcePath): InputStream = when (location) {
        is LibraryLocation.Local -> {
            if (state.current()?.location != location) throw FormatSourceFailure(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED))
            val tree = state.localTreeUri() ?: throw FormatSourceFailure(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED))
            when (val result = local.openRead(tree, path)) {
                is LocalSourceResult.Available -> result.value
                is LocalSourceResult.Failed -> throw FormatSourceFailure(result.error)
            }
        }
        is LibraryLocation.OneDrive -> when (val result = oneDrive.openCover(location, path, CoverRepository.WIDTH, CoverRepository.HEIGHT)) {
            is OneDriveSourceResult.Available -> result.value.networkFailures()
            is OneDriveSourceResult.Failed -> throw FormatSourceFailure(result.error, result.transient, result.retryDelayMillis)
        }
    }
}
