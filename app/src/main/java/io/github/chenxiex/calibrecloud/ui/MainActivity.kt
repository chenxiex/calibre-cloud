package io.github.chenxiex.calibrecloud.ui

import android.content.Intent
import android.os.Build
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.BackendKind
import java.text.DateFormat
import java.util.Date
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.auth.OneDriveOAuthConfiguration
import io.github.chenxiex.calibrecloud.auth.LoginStatus
import io.github.chenxiex.calibrecloud.auth.LoginIssue
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationState
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus
import io.github.chenxiex.calibrecloud.storage.local.DirectorySelectionIssue

class MainActivity : ComponentActivity() {
    private val authorizationModel by lazy {
        ViewModelProvider(this, LocalDirectoryAuthorizationViewModel.factory(applicationContext))[
            LocalDirectoryAuthorizationViewModel::class.java,
        ]
    }
    private val oneDriveModel by lazy {
        ViewModelProvider(this, OneDriveAuthorizationViewModel.factory(applicationContext))[
            OneDriveAuthorizationViewModel::class.java,
        ]
    }
    private val snapshotModel by lazy {
        ViewModelProvider(this, LocalSnapshotViewModel.factory(applicationContext))[LocalSnapshotViewModel::class.java]
    }
    private val oneDriveLibraryModel by lazy {
        ViewModelProvider(this, OneDriveLibraryViewModel.factory(applicationContext))[OneDriveLibraryViewModel::class.java]
    }
    private val metadataModel by lazy {
        ViewModelProvider(this, MetadataViewModel.factory(applicationContext))[MetadataViewModel::class.java]
    }
    private val downloadModel by lazy {
        ViewModelProvider(this, DownloadViewModel.factory(applicationContext))[DownloadViewModel::class.java]
    }
    private val coverModel by lazy {
        ViewModelProvider(this, CoverViewModel.factory(applicationContext))[CoverViewModel::class.java]
    }
    private val cleanupModel by lazy {
        ViewModelProvider(this, CleanupViewModel.factory(applicationContext))[CleanupViewModel::class.java]
    }
    private val libraryModel by lazy {
        ViewModelProvider(this, LibraryViewModel.factory(applicationContext))[LibraryViewModel::class.java]
    }
    private val taskModel by lazy {
        ViewModelProvider(this, TaskViewModel.factory(applicationContext))[TaskViewModel::class.java]
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        taskModel.refreshCapabilities()
    }
    private var pickerOpen by mutableStateOf(false)
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerOpen = false
        authorizationModel.select(
            if (result.resultCode == RESULT_OK) result.data?.data?.toString() else null,
            result.data?.flags ?: 0,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerOpen = savedInstanceState?.getBoolean("picker_open") ?: false
        intent.data?.toString()?.let { oneDriveModel.callback(it); intent.data = null }
        val dependencies = (application as io.github.chenxiex.calibrecloud.CalibreCloudApplication).dependencies
        dependencies.applicationScope.launch { dependencies.backgroundTasks.onMainOpened() }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White, onBackground = Color.Black)) {
                MainScreen(libraryModel) { page, onPage ->
                    AuthorizationPage(authorizationModel.state, authorizationModel.busy || pickerOpen, snapshotModel, oneDriveModel, oneDriveLibraryModel, metadataModel, downloadModel, coverModel, cleanupModel, taskModel,
                        page, onPage,
                        { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        { oneDriveModel.login { startActivity(it) } }) {
                        pickerOpen = true
                        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                        ))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { oneDriveModel.callback(it) }
        intent.data = null
    }

    override fun onResume() {
        super.onResume()
        oneDriveModel.restore()
        oneDriveLibraryModel.restore()
        metadataModel.restore()
        downloadModel.restore()
        coverModel.restore()
        libraryModel.refresh()
        if (pickerOpen) return
        authorizationModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker_open", pickerOpen)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun AuthorizationPage(
    state: DirectoryAuthorizationState,
    busy: Boolean,
    snapshot: LocalSnapshotViewModel,
    oneDrive: OneDriveAuthorizationViewModel,
    library: OneDriveLibraryViewModel,
    metadata: MetadataViewModel,
    downloads: DownloadViewModel,
    covers: CoverViewModel,
    cleanup: CleanupViewModel,
    tasks: TaskViewModel,
    page: Int,
    onPage: (Int) -> Unit,
    onRequestNotifications: () -> Unit,
    onLogin: () -> Unit,
    onSelect: () -> Unit,
) {
    LaunchedEffect(state, busy, oneDrive.status) {
        if (!busy) {
            snapshot.restore()
            library.restore()
        }
    }
    // Temporary verification entries stay reachable from "更多" until the formal pages replace them.
    LaunchedEffect(page, state, busy, snapshot.record, library.record, library.rootChosen, library.submitting) {
        metadata.restore()
        downloads.restore()
    }
    LaunchedEffect(page, state, busy, snapshot.record, library.record, library.rootChosen, library.submitting) {
        covers.setVisible(page == 8)
    }
    LaunchedEffect(page) { tasks.setVisible(page >= 10) }
    DisposableEffect(Unit) {
        onDispose {
            covers.setVisible(false)
            tasks.setVisible(false)
        }
    }
    LaunchedEffect(cleanup.revision) {
        metadata.restore()
        downloads.restore()
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White)
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.more_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f)) {
            if (page == 0) {
                Text(stringResource(R.string.local_authorization), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (state.status) {
                    DirectoryAuthorizationStatus.UNSELECTED -> R.string.local_unselected
                    DirectoryAuthorizationStatus.AUTHORIZED -> R.string.local_authorized
                    DirectoryAuthorizationStatus.READ_ONLY -> R.string.local_read_only
                    DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED -> R.string.local_reauthorize
                    DirectoryAuthorizationStatus.UNSUPPORTED_PROVIDER -> R.string.local_unsupported
                }))
                state.selectionIssue?.let {
                    Text(stringResource(when (it) {
                        DirectorySelectionIssue.UNSUPPORTED_PROVIDER -> R.string.local_unsupported
                        DirectorySelectionIssue.PERSISTENCE_FAILED -> R.string.local_persistence_failed
                        DirectorySelectionIssue.CONFIGURATION_FAILED -> R.string.local_configuration_failed
                    }))
                }
                Spacer(Modifier.height(16.dp))
                StaticButton(stringResource(if (busy) R.string.local_checking else R.string.local_select), !busy) { onSelect() }
                Spacer(Modifier.height(12.dp))
                LocalSnapshotControls(snapshot, !busy && state.status in setOf(
                    DirectoryAuthorizationStatus.AUTHORIZED, DirectoryAuthorizationStatus.READ_ONLY))
            } else if (page == 1) {
                Text(stringResource(R.string.onedrive_authorization), style = MaterialTheme.typography.titleMedium)
                if (OneDriveOAuthConfiguration.fromBuildConfiguration() == null) {
                    Text(stringResource(R.string.onedrive_unavailable))
                } else {
                    Text(stringResource(when (oneDrive.status) {
                        LoginStatus.UNSIGNED -> R.string.onedrive_configured
                        LoginStatus.BROWSER -> R.string.onedrive_browser
                        LoginStatus.EXCHANGING -> R.string.onedrive_exchanging
                        LoginStatus.AUTHORIZED -> R.string.onedrive_authorized
                        LoginStatus.RELOGIN -> R.string.onedrive_relogin
                    }))
                    oneDrive.issue?.let { Text(stringResource(when (it) {
                        LoginIssue.CANCELED -> R.string.onedrive_canceled
                        LoginIssue.NETWORK -> R.string.onedrive_network
                        LoginIssue.NO_BROWSER -> R.string.onedrive_no_browser
                        LoginIssue.SERVER -> R.string.onedrive_server
                        LoginIssue.CALLBACK -> R.string.onedrive_callback
                        LoginIssue.STORAGE -> R.string.onedrive_storage
                        LoginIssue.RELOGIN -> R.string.onedrive_relogin
                        LoginIssue.EXPIRED -> R.string.onedrive_expired
                    })) }
                    Spacer(Modifier.height(16.dp))
                    if (oneDrive.status == LoginStatus.BROWSER) {
                        StaticButton(stringResource(R.string.onedrive_cancel), !oneDrive.busy) { oneDrive.cancel() }
                    } else {
                        StaticButton(stringResource(R.string.onedrive_login), !oneDrive.busy) { onLogin() }
                        if (oneDrive.status == LoginStatus.AUTHORIZED) {
                            Spacer(Modifier.height(8.dp))
                            StaticButton(stringResource(R.string.onedrive_refresh), !oneDrive.busy) { oneDrive.refresh() }
                        }
                    }
                }
            } else if (page == 2) {
                OneDriveDirectoryControls(library, oneDrive.status == LoginStatus.AUTHORIZED && !oneDrive.busy)
            } else if (page == 3) {
                OneDriveTaskControls(library, oneDrive.status == LoginStatus.AUTHORIZED && !oneDrive.busy)
            } else if (page == 4) {
                MetadataSummary(metadata)
            } else if (page == 5) {
                MetadataColumnControls(metadata)
            } else if (page == 6) {
                DownloadControls(downloads)
            } else if (page == 7) {
                DownloadList(downloads)
            } else if (page == 8) {
                CoverScreen(covers)
            } else if (page == 9) {
                CleanupScreen(cleanup)
            } else if (page == 10) {
                TaskScreen(tasks)
            } else if (page == 11) {
                TaskSettings(tasks)
            } else {
                BackgroundSettings(tasks, onRequestNotifications)
            }
        }
        Text(stringResource(R.string.authorization_page_number, page + 1, 13))
        Row {
            StaticButton(stringResource(R.string.page_previous), page > 0 && !cleanup.busy) { onPage(page - 1) }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.page_next), page < 12 && !cleanup.busy) { onPage(page + 1) }
        }
    }
}

@Composable
internal fun StaticButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // A disabled button is grey with a thinner border so the state does not rely on colour alone.
    val tint = if (enabled) Color.Black else Color(0xFF8A8A8A)
    Box(modifier.border(if (enabled) 1.dp else 0.5.dp, tint).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, role = Role.Button, onClick = onClick,
    ).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LocalSnapshotControls(model: LocalSnapshotViewModel, authorized: Boolean) {
    val record = model.record
    val taskState = record?.state
    val active = taskState != null && taskState !is TaskState.Finished
    val status = when (taskState) {
        TaskState.Queued -> R.string.local_snapshot_queued
        is TaskState.Running -> R.string.local_snapshot_running
        is TaskState.Waiting -> if (io.github.chenxiex.calibrecloud.tasks.api.WaitingReason.DIRECTORY_AUTHORIZATION in taskState.reasons)
            R.string.local_reauthorize else R.string.local_snapshot_waiting
        is TaskState.Paused -> R.string.local_snapshot_paused
        is TaskState.Finished -> when (val result = taskState.result) {
            TaskResult.Completed -> R.string.local_snapshot_ready
            is TaskResult.Cancelled -> R.string.local_snapshot_cancelled
            is TaskResult.Failed -> when ((result.failure.error as? TaskError.Source)?.error?.kind) {
                StorageErrorKind.AUTHORIZATION_EXPIRED -> R.string.local_reauthorize
                StorageErrorKind.SOURCE_MISSING -> R.string.local_snapshot_missing
                StorageErrorKind.VERSION_CONFLICT -> R.string.local_snapshot_conflict
                StorageErrorKind.CORRUPT_CONTENT -> R.string.local_snapshot_corrupt
                StorageErrorKind.INCOMPATIBLE_DATABASE -> R.string.metadata_incompatible
                StorageErrorKind.INSUFFICIENT_SPACE -> R.string.local_snapshot_space
                StorageErrorKind.UNSUPPORTED_OPERATION -> R.string.local_unsupported
                else -> R.string.local_snapshot_io
            }
            else -> R.string.local_snapshot_io
        }
        null -> if (model.rejected) R.string.local_snapshot_rejected else R.string.local_snapshot_pending
    }
    Text(stringResource(status))
    Spacer(Modifier.height(8.dp))
    StaticButton(stringResource(R.string.local_snapshot_acquire), authorized && !active && !model.submitting) { model.acquire() }
    record?.controls?.let { controls ->
        if (controls.canPause) StaticButton(stringResource(R.string.local_snapshot_pause), true) { model.control(TaskControl.PAUSE) }
        if (controls.canResume) StaticButton(stringResource(R.string.local_snapshot_resume), true) { model.control(TaskControl.RESUME) }
        if (controls.canRetry) StaticButton(stringResource(R.string.local_snapshot_retry), authorized) { model.control(TaskControl.RETRY) }
        if (controls.canCancel) StaticButton(stringResource(R.string.local_snapshot_cancel), true) { model.control(TaskControl.CANCEL) }
        if (taskState == TaskState.Queued || taskState is TaskState.Waiting) {
            StaticButton(stringResource(R.string.local_snapshot_run), authorized && !model.submitting) { model.acquire() }
        }
    }
}

@Composable
private fun OneDriveDirectoryControls(model: OneDriveLibraryViewModel, authorized: Boolean) {
    Text(stringResource(R.string.onedrive_directory), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(if (model.rootChosen) R.string.onedrive_root_chosen else R.string.onedrive_root_pending))
    StaticButton(stringResource(R.string.onedrive_browse), authorized && !model.submitting) { model.browse() }
    Text(stringResource(directoryStatusResource(
        model.record?.state,
        (model.record?.submission?.request as? TaskRequest.CandidateConfiguration)?.operation,
        model.rejected,
        model.submitting,
    )), maxLines = 2, overflow = TextOverflow.Ellipsis)
    val page = model.page
    if (page != null) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onedrive_current_directory, page.directoryName), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.onedrive_directory_page, page.page + 1))
        val directories = page.items.filter { it.directory }
        if (directories.isEmpty()) Text(stringResource(R.string.onedrive_directory_empty))
        directories.take(3).forEach { item ->
            Spacer(Modifier.height(4.dp))
            StaticButton(item.name, authorized && !model.submitting) { model.enter(item.id) }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            StaticButton(stringResource(R.string.page_previous), authorized && !model.submitting && page.page > 0) {
                model.paginate(page.page - 1)
            }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.page_next), authorized && !model.submitting && page.hasNext) {
                model.paginate(page.page + 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            StaticButton(stringResource(R.string.onedrive_directory_up), authorized && !model.submitting && model.canGoUp) { model.up() }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.onedrive_directory_choose), authorized && !model.submitting) {
                model.choose(page.parentItemId)
            }
        }
    }
}

/** Shared text mapping keeps browser errors visible beside the control that submitted them. */
internal fun oneDriveTaskStatusResource(taskState: TaskState?, operation: String?, rejected: Boolean): Int {
    return when (taskState) {
        TaskState.Queued -> R.string.onedrive_task_queued
        is TaskState.Running -> R.string.onedrive_task_running
        is TaskState.Waiting -> when {
            io.github.chenxiex.calibrecloud.tasks.api.WaitingReason.NETWORK in taskState.reasons -> R.string.onedrive_task_network
            io.github.chenxiex.calibrecloud.tasks.api.WaitingReason.THROTTLED in taskState.reasons -> R.string.task_wait_throttled
            io.github.chenxiex.calibrecloud.tasks.api.WaitingReason.LOGIN in taskState.reasons -> R.string.onedrive_relogin
            else -> R.string.onedrive_task_waiting
        }
        is TaskState.Paused -> R.string.local_snapshot_paused
        is TaskState.Finished -> when (val result = taskState.result) {
            TaskResult.Completed -> if (operation ==
                OneDriveCandidateTaskHandler.SNAPSHOT) R.string.local_snapshot_ready else R.string.onedrive_browse_completed
            is TaskResult.Cancelled -> R.string.onedrive_task_cancelled
            is TaskResult.Failed -> when ((result.failure.error as? TaskError.Source)?.error?.kind) {
                StorageErrorKind.NO_NETWORK -> R.string.onedrive_task_network_failed
                StorageErrorKind.THROTTLED -> R.string.task_error_throttled
                StorageErrorKind.LOGIN_REQUIRED -> R.string.onedrive_relogin
                StorageErrorKind.AUTHORIZATION_EXPIRED -> R.string.onedrive_task_permission
                StorageErrorKind.SOURCE_MISSING -> R.string.local_snapshot_missing
                StorageErrorKind.VERSION_CONFLICT -> R.string.local_snapshot_conflict
                StorageErrorKind.CORRUPT_CONTENT -> R.string.local_snapshot_corrupt
                StorageErrorKind.INCOMPATIBLE_DATABASE -> R.string.metadata_incompatible
                StorageErrorKind.INSUFFICIENT_SPACE -> R.string.local_snapshot_space
                StorageErrorKind.UNSUPPORTED_OPERATION -> R.string.onedrive_task_unsupported
                else -> R.string.onedrive_task_io
            }
            else -> R.string.onedrive_task_io
        }
        null -> if (rejected) R.string.onedrive_task_rejected else R.string.onedrive_task_pending
    }
}

/** Snapshot tasks belong to the task page and cannot stand in for directory results. */
internal fun directoryStatusResource(taskState: TaskState?, operation: String?, rejected: Boolean, submitting: Boolean): Int {
    if (rejected) return R.string.onedrive_task_rejected
    if (operation == OneDriveCandidateTaskHandler.SNAPSHOT) return R.string.onedrive_task_pending
    if (submitting && (taskState == null || taskState is TaskState.Finished)) return R.string.onedrive_task_queued
    return oneDriveTaskStatusResource(
        if (operation == OneDriveCandidateTaskHandler.BROWSE) taskState else null,
        OneDriveCandidateTaskHandler.BROWSE,
        false,
    )
}

@Composable
private fun OneDriveTaskControls(model: OneDriveLibraryViewModel, authorized: Boolean) {
    Text(stringResource(R.string.onedrive_tasks), style = MaterialTheme.typography.titleMedium)
    val taskState = model.record?.state
    val status = oneDriveTaskStatusResource(
        taskState, (model.record?.submission?.request as? TaskRequest.CandidateConfiguration)?.operation, model.rejected,
    )
    Text(stringResource(status))
    Spacer(Modifier.height(8.dp))
    val active = taskState != null && taskState !is TaskState.Finished
    StaticButton(stringResource(R.string.local_snapshot_acquire), authorized && model.rootChosen && !active && !model.submitting) { model.acquire() }
    model.record?.controls?.let { controls ->
        Spacer(Modifier.height(8.dp))
        if (controls.canPause) StaticButton(stringResource(R.string.local_snapshot_pause), true) { model.control(TaskControl.PAUSE) }
        if (controls.canResume) StaticButton(stringResource(R.string.local_snapshot_resume), authorized) { model.control(TaskControl.RESUME) }
        if (controls.canRetry) StaticButton(stringResource(R.string.local_snapshot_retry), authorized) { model.control(TaskControl.RETRY) }
        if (controls.canCancel) StaticButton(stringResource(R.string.onedrive_task_cancel), true) { model.control(TaskControl.CANCEL) }
        if (taskState == TaskState.Queued || taskState is TaskState.Waiting) {
            StaticButton(stringResource(R.string.local_snapshot_run), authorized && !model.submitting) { model.runQueued() }
        }
    }
}

@Composable
private fun MetadataSummary(model: MetadataViewModel) {
    Text(stringResource(R.string.metadata_title), style = MaterialTheme.typography.titleMedium)
    val selected = model.selection
    val imported = model.imported
    if (model.readFailed) {
        Text(stringResource(R.string.metadata_local_error))
    } else if (selected == null) {
        Text(stringResource(R.string.metadata_unselected))
    } else {
        Text(stringResource(R.string.metadata_backend, stringResource(when (selected.backend) {
            BackendKind.LOCAL -> R.string.metadata_backend_local
            BackendKind.ONEDRIVE -> R.string.metadata_backend_onedrive
        })))
        if (imported == null) {
            Text(stringResource(R.string.metadata_unvalidated))
        } else {
            Text(stringResource(R.string.metadata_library_id, imported.identity.id.value.toString()))
            Text(stringResource(R.string.metadata_import_ready))
            Text(stringResource(R.string.metadata_counts, imported.metadata.books.size,
                imported.metadata.books.sumOf { it.formats.size }))
            Text(stringResource(R.string.metadata_synced_at,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(imported.importedAt))))
        }
    }
    Spacer(Modifier.height(12.dp))
    StaticButton(stringResource(R.string.metadata_refresh_local), !model.busy) { model.restore() }
}

@Composable
private fun MetadataColumnControls(model: MetadataViewModel) {
    Text(stringResource(R.string.metadata_columns_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.metadata_columns_explanation))
    val imported = model.imported
    if (model.readFailed) {
        Text(stringResource(R.string.metadata_local_error))
    } else if (imported == null) {
        Text(stringResource(R.string.metadata_unvalidated))
    } else {
        if (imported.readColumnStatus == ReadColumnStatus.VALID) {
            val selected = imported.metadata.columns.first { it.id == imported.selectedReadColumn }
            Text(stringResource(R.string.metadata_column_selected, selected.name, selected.id.lookupName))
        } else {
            Text(stringResource(if (imported.readColumnStatus == ReadColumnStatus.INVALID)
                R.string.metadata_columns_invalid else R.string.metadata_columns_unconfigured))
        }
        val columns = imported.metadata.columns.filter { it.datatype == "bool" && it.supported }
        var columnPage by rememberSaveable(imported.identity.id.value.toString(), imported.generation.toString()) {
            mutableIntStateOf(0)
        }
        val pageCount = maxOf(1, (columns.size + 2) / 3)
        val currentPage = columnPage.coerceIn(0, pageCount - 1)
        if (columns.isEmpty()) Text(stringResource(R.string.metadata_columns_empty))
        columns.drop(currentPage * 3).take(3).forEach { column ->
            Spacer(Modifier.height(4.dp))
            StaticButton(stringResource(R.string.metadata_column_option, column.name, column.id.lookupName),
                !model.busy && column.id != imported.selectedReadColumn) { model.selectColumn(column.id) }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.metadata_columns_page, currentPage + 1, pageCount))
        Row {
            StaticButton(stringResource(R.string.page_previous), !model.busy && currentPage > 0) { columnPage = currentPage - 1 }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.page_next), !model.busy && currentPage + 1 < pageCount) { columnPage = currentPage + 1 }
        }
        Spacer(Modifier.height(8.dp))
        StaticButton(stringResource(R.string.metadata_columns_clear), !model.busy && imported.selectedReadColumn != null) {
            model.selectColumn(null)
        }
    }
    if (model.configurationFailed) Text(stringResource(R.string.metadata_configuration_failed))
}
