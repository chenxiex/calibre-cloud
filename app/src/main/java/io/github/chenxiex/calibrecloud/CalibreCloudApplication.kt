package io.github.chenxiex.calibrecloud

import android.app.Application
import android.content.Context
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.DatabaseLocalDirectoryConfiguration
import io.github.chenxiex.calibrecloud.storage.cache.PrivateCopyReader
import io.github.chenxiex.calibrecloud.storage.local.AndroidDirectoryPermissions
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryAuthorization
import io.github.chenxiex.calibrecloud.storage.local.PreferencesLocalDirectoryConfiguration
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import io.github.chenxiex.calibrecloud.tasks.local.LocalSnapshotTaskHandler
import io.github.chenxiex.calibrecloud.storage.local.AndroidLocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.local.AndroidSnapshotValidator
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackend
import java.io.File
import kotlinx.coroutines.Dispatchers

/** One process-owned state store and reader; lazy construction performs no source or database I/O. */
class ApplicationDependencies(context: Context) {
    private val applicationContext = context.applicationContext
    val oneDriveAuthorization by lazy {
        io.github.chenxiex.calibrecloud.auth.OneDriveAuthorization(applicationContext,
            io.github.chenxiex.calibrecloud.auth.OneDriveOAuthConfiguration.fromBuildConfiguration(),
            io.github.chenxiex.calibrecloud.auth.EncryptedAuthStateStore(applicationContext))
    }
    val database by lazy { ApplicationStateDatabase(applicationContext) }
    private val bookFiles by lazy { PrivateBookFiles(applicationContext.filesDir) }
    val state by lazy { ApplicationStateRepository(database, bookFiles, Dispatchers.IO) }
    val metadata by lazy {
        io.github.chenxiex.calibrecloud.metadata.MetadataRepository(database, state,
            File(applicationContext.filesDir, "metadata"), Dispatchers.IO)
    }
    val taskQueue by lazy { DurableTaskQueue(database, Dispatchers.IO) }
    val localBackend by lazy {
        LocalSourceBackend(AndroidLocalDocumentAccess(applicationContext),
            File(applicationContext.filesDir, "snapshots/local"), AndroidSnapshotValidator(), Dispatchers.IO)
    }
    val oneDriveBackend by lazy {
        io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceBackend(
            { force -> oneDriveAuthorization.backendAccessToken(force,
                kotlinx.coroutines.currentCoroutineContext()[io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationSession]?.id) },
            File(applicationContext.filesDir, "snapshots/onedrive"), AndroidSnapshotValidator(), Dispatchers.IO,
            diagnostic = { reason -> android.util.Log.w("OneDriveSource", "stage=$reason") },
            accountProvider = { oneDriveAuthorization.accountSubject(
                kotlinx.coroutines.currentCoroutineContext()[io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationSession]?.id) })
    }
    val oneDriveBrowseStore by lazy {
        io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveBrowseStore(File(applicationContext.filesDir, "onedrive-browser"))
    }
    val oneDriveTasks by lazy {
        io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateService(
            state, oneDriveAuthorization, taskQueue, taskCoordinator, oneDriveBrowseStore)
    }
    val taskCoordinator by lazy {
        TaskCoordinator(taskQueue, listOf(LocalSnapshotTaskHandler(state, localBackend, metadata, Dispatchers.IO),
            io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler(
                state, oneDriveAuthorization, oneDriveBackend, oneDriveBrowseStore, metadata)))
    }
    val copyReader by lazy { PrivateCopyReader(state, bookFiles, Dispatchers.IO) }
    private val localPermissions by lazy { AndroidDirectoryPermissions(applicationContext) }
    val localConfiguration by lazy {
        DatabaseLocalDirectoryConfiguration(state, localPermissions, PreferencesLocalDirectoryConfiguration(applicationContext))
    }
    val localAuthorization by lazy {
        LocalDirectoryAuthorization(localPermissions, localConfiguration, Dispatchers.IO)
    }
}

class CalibreCloudApplication : Application() {
    val dependencies by lazy { ApplicationDependencies(this) }
}
