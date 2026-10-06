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
    val database by lazy { ApplicationStateDatabase(applicationContext) }
    private val bookFiles by lazy { PrivateBookFiles(applicationContext.filesDir) }
    val state by lazy { ApplicationStateRepository(database, bookFiles, Dispatchers.IO) }
    val taskQueue by lazy { DurableTaskQueue(database, Dispatchers.IO) }
    val localBackend by lazy {
        LocalSourceBackend(AndroidLocalDocumentAccess(applicationContext),
            File(applicationContext.filesDir, "snapshots/local"), AndroidSnapshotValidator(), Dispatchers.IO)
    }
    val taskCoordinator by lazy {
        TaskCoordinator(taskQueue, listOf(LocalSnapshotTaskHandler(state, localBackend, Dispatchers.IO)))
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
