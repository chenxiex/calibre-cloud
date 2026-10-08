package io.github.chenxiex.calibrecloud.storage

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.storage.api.LibrarySources
import io.github.chenxiex.calibrecloud.storage.local.LocalDocument
import io.github.chenxiex.calibrecloud.storage.local.LocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.local.LocalLibrarySource
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackend
import io.github.chenxiex.calibrecloud.storage.local.SnapshotValidator
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveLibrarySource
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceBackend
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers

/**
 * The production sources of every backend, for their declared policies only. Source I/O fixtures
 * delegate to these, so handler tests follow the real per-backend rules; calling their I/O fails.
 */
object SourcePolicies {
    val sources: LibrarySources by lazy {
        val unused = File("/nonexistent")
        val local = LocalLibrarySource(LocalSourceBackend(object : LocalDocumentAccess {
            override fun root(treeUri: String): LocalDocument = error("No local I/O")
            override fun children(treeUri: String, parentId: String): List<LocalDocument> = error("No local I/O")
            override fun isWithinRoot(treeUri: String, documentId: String): Boolean = error("No local I/O")
            override fun openRead(treeUri: String, documentId: String): InputStream = error("No local I/O")
        }, unused, SnapshotValidator { false }, Dispatchers.IO)) { null }
        val oneDrive = OneDriveLibrarySource(OneDriveSourceBackend({ null }, unused, SnapshotValidator { false }, Dispatchers.IO))
        LibrarySources { backend ->
            when (backend) {
                BackendKind.LOCAL -> local
                BackendKind.ONEDRIVE -> oneDrive
            }
        }
    }
}
