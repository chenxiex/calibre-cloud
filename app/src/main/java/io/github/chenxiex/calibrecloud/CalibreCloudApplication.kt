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
import io.github.chenxiex.calibrecloud.storage.local.AndroidLocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.local.AndroidSnapshotValidator
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackend
import java.io.File
import kotlinx.coroutines.Dispatchers

/** One process-owned state store and reader; lazy construction performs no source or database I/O. */
class ApplicationDependencies(context: Context) {
    private val applicationContext = context.applicationContext
    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
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
    val maintenance: io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance by lazy { io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance(database, state, taskQueue,
        applicationContext.filesDir, Dispatchers.IO) }
    val taskQueue: DurableTaskQueue by lazy { DurableTaskQueue(database, Dispatchers.IO).apply {
        beforeDispatch = { maintenance.recoverLocked() }
        onWake = { backgroundTasks.wake() }
    } }
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
    val oneDriveTasks: io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateService by lazy {
        io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateService(
            state, oneDriveAuthorization, taskQueue, taskCoordinator, oneDriveBrowseStore)
    }
    /** The only place that maps a backend kind to its implementation of the common source contract. */
    val librarySources: io.github.chenxiex.calibrecloud.storage.api.LibrarySources by lazy {
        val local = io.github.chenxiex.calibrecloud.storage.local.LocalLibrarySource(localBackend) { location ->
            if (state.current()?.location == location) state.localTreeUri() else null
        }
        val oneDrive = io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveLibrarySource(oneDriveBackend)
        io.github.chenxiex.calibrecloud.storage.api.LibrarySources { backend ->
            when (backend) {
                io.github.chenxiex.calibrecloud.model.BackendKind.LOCAL -> local
                io.github.chenxiex.calibrecloud.model.BackendKind.ONEDRIVE -> oneDrive
            }
        }
    }
    /** The only place that maps a backend kind to its authorization as library syncs see it. */
    val libraryAuthorizations: io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorizations by lazy {
        val local = io.github.chenxiex.calibrecloud.tasks.local.LocalLibraryAuthorization {
            localAuthorization.restore().status in setOf(io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus.AUTHORIZED,
                io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus.READ_ONLY)
        }
        val oneDrive = io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveLibraryAuthorization(state,
            { oneDriveAuthorization.sessionId() }, { oneDriveAuthorization.issue })
        io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorizations { backend ->
            when (backend) {
                io.github.chenxiex.calibrecloud.model.BackendKind.LOCAL -> local
                io.github.chenxiex.calibrecloud.model.BackendKind.ONEDRIVE -> oneDrive
            }
        }
    }
    /** Syncs the current library whatever its backend; also the stale-path sync of R11. */
    val librarySync: io.github.chenxiex.calibrecloud.tasks.background.StartupSync by lazy {
        io.github.chenxiex.calibrecloud.tasks.background.StartupSync(state, taskCoordinator, libraryAuthorizations)
    }
    val libraryQuery by lazy {
        io.github.chenxiex.calibrecloud.library.LibraryQueryService(
            io.github.chenxiex.calibrecloud.library.MetadataLibraryImports(metadata),
            io.github.chenxiex.calibrecloud.library.StateLibraryCopies(state), Dispatchers.Default)
    }
    val searchHistory by lazy { io.github.chenxiex.calibrecloud.state.SearchHistoryRepository(database, Dispatchers.IO) }
    val copyService by lazy { io.github.chenxiex.calibrecloud.tasks.copies.CopyService(state, metadata, taskCoordinator, taskQueue) }
    val covers by lazy { io.github.chenxiex.calibrecloud.storage.covers.CoverRepository(database, state,
        applicationContext.filesDir, Dispatchers.IO) }
    val coverService by lazy { io.github.chenxiex.calibrecloud.tasks.covers.CoverService(state, metadata, taskCoordinator) }
    val backgroundTasks by lazy { io.github.chenxiex.calibrecloud.tasks.background.BackgroundTasks(applicationContext, this) }
    private val queueConditions by lazy { io.github.chenxiex.calibrecloud.tasks.background.QueueConditions(applicationContext, database, librarySources) }
    val taskCoordinator: TaskCoordinator by lazy {
        TaskCoordinator(taskQueue, listOf(
            io.github.chenxiex.calibrecloud.tasks.sync.LibrarySyncTaskHandler(state, librarySources, libraryAuthorizations, metadata, Dispatchers.IO),
            io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler(state,
                libraryAuthorizations.of(io.github.chenxiex.calibrecloud.model.BackendKind.ONEDRIVE), oneDriveBackend,
                librarySources.of(io.github.chenxiex.calibrecloud.model.BackendKind.ONEDRIVE), oneDriveBrowseStore),
            io.github.chenxiex.calibrecloud.tasks.copies.FormatCopyTaskHandler(state, metadata, taskQueue, librarySources,
                applicationContext.filesDir, Dispatchers.IO, requestSync = { origin -> librarySync.request(origin) }),
            io.github.chenxiex.calibrecloud.tasks.covers.CoverTaskHandler(state, metadata, covers, librarySources, Dispatchers.IO,
                taskQueue) { origin -> librarySync.request(origin) }), conditions = queueConditions::waiting)
    }
    val copyReader by lazy { PrivateCopyReader(state, bookFiles, Dispatchers.IO, state.copyAccess) }
    val bookCatalog by lazy { io.github.chenxiex.calibrecloud.files.StateBookCatalog(state, applicationContext.filesDir) }
    val lastOpened by lazy { io.github.chenxiex.calibrecloud.state.LastOpenedRepository(database, Dispatchers.IO) }
    private val localPermissions by lazy { AndroidDirectoryPermissions(applicationContext) }
    val localConfiguration by lazy {
        DatabaseLocalDirectoryConfiguration(state, localPermissions, PreferencesLocalDirectoryConfiguration(applicationContext))
    }
    val localAuthorization by lazy {
        LocalDirectoryAuthorization(localPermissions, localConfiguration, Dispatchers.IO)
    }
}

class CalibreCloudApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
    val dependencies by lazy { ApplicationDependencies(this) }
}
