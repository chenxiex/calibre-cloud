package io.github.chenxiex.calibrecloud.ui

import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl

@Composable
internal fun TaskScreen(model: TaskViewModel) {
    TaskList(model.records, model.failed, model.operationFailed, model::control)
}

@Composable
internal fun TaskList(records: List<TaskRecord>, failed: Boolean, operationFailed: Boolean = false, onControl: (TaskRecord, TaskControl) -> Unit) {
    Text(stringResource(R.string.task_title), style = MaterialTheme.typography.titleMedium)
    if (failed) Text(stringResource(R.string.task_local_error))
    if (operationFailed) Text(stringResource(R.string.task_control_error))
    var page by rememberSaveable { mutableIntStateOf(0) }
    val count = maxOf(1, records.size)
    val current = page.coerceIn(0, count - 1)
    val record = records.getOrNull(current)
    if (record == null) {
        Text(stringResource(R.string.task_empty))
    } else {
        Text(stringResource(taskTypeResource(record.submission.request)))
        Text(stringResource(if (record.effectiveOrigin.priority == TaskPriority.HIGH)
            R.string.task_user else R.string.task_automatic))
        if (record.promotion != null) Text(stringResource(R.string.task_promoted))
        if (record.state == TaskState.Queued || record.state is TaskState.Waiting) {
            val pending = records.filter { it.state == TaskState.Queued || it.state is TaskState.Waiting }
            Text(stringResource(R.string.task_order, pending.indexOf(record) + 1))
        }
        TaskStatus(record)
        Spacer(Modifier.height(8.dp))
        val controls = record.controls
        Row {
            if (controls.canPause) StaticButton(stringResource(R.string.task_pause), true) { onControl(record, TaskControl.PAUSE) }
            if (controls.canResume) StaticButton(stringResource(R.string.task_resume), true) { onControl(record, TaskControl.RESUME) }
            if (controls.canRetry) StaticButton(stringResource(R.string.task_retry), true) { onControl(record, TaskControl.RETRY) }
            if (controls.canCancel) {
                Spacer(Modifier.width(8.dp))
                StaticButton(stringResource(R.string.task_cancel), true) { onControl(record, TaskControl.CANCEL) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.task_page, current + 1, count))
    Row {
        StaticButton(stringResource(R.string.task_previous), current > 0) { page = current - 1 }
        Spacer(Modifier.width(8.dp))
        StaticButton(stringResource(R.string.task_next), current + 1 < count) { page = current + 1 }
    }
}

@Composable
private fun TaskStatus(record: TaskRecord) {
    if (record.restartedTransfer && (record.state is TaskState.Running || record.state is TaskState.Paused)) {
        Text(stringResource(R.string.task_transfer_restarted))
    }
    when (val state = record.state) {
        TaskState.Queued -> Text(stringResource(R.string.task_queued))
        is TaskState.Waiting -> {
            Text(stringResource(R.string.task_waiting))
            state.reasons.forEach { Text(stringResource(waitingResource(it))) }
        }
        is TaskState.Running -> {
            Text(stringResource(R.string.task_running, stringResource(stageResource(state.stage))))
            val progress = state.progress
            Text(if (progress?.total == null) stringResource(R.string.task_progress_unknown)
                else stringResource(R.string.task_progress, progress.completed, progress.total))
            if (state.stage == TaskStage.WRITE_COMMIT) Text(stringResource(R.string.task_committing))
        }
        is TaskState.Paused -> Text(stringResource(R.string.task_paused, stringResource(stageResource(state.stage))))
        is TaskState.Finished -> when (val result = state.result) {
            TaskResult.Completed -> Text(stringResource(R.string.task_completed))
            is TaskResult.CompletedWithBookFailures -> Text(stringResource(R.string.task_partial, result.failures.size))
            is TaskResult.Cancelled -> Text(stringResource(if (result.commit == CommitState.NotCommitted)
                R.string.task_cancelled else R.string.task_cancelled_committed))
            is TaskResult.Failed -> {
                Text(stringResource(R.string.task_failed, stringResource(stageResource(result.failure.stage))))
                Text(stringResource(taskErrorResource(result.failure.error)))
                Text(stringResource(when (result.failure.retryFrom) {
                    RetryFrom.FAILED_STAGE -> R.string.task_retry_stage
                    RetryFrom.WRITE_REFETCH -> R.string.task_retry_refresh
                    RetryFrom.RECOVERY_CHECK -> R.string.task_retry_recovery
                }))
            }
        }
    }
}

@Composable
internal fun TaskSettings(model: TaskViewModel) {
    Text(stringResource(R.string.task_settings), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.task_startup_explanation))
    Spacer(Modifier.height(8.dp))
    StaticButton(stringResource(if (!model.settingsLoaded) R.string.task_settings_loading
        else if (model.automaticSync) R.string.task_startup_on else R.string.task_startup_off), model.settingsLoaded) {
        model.toggleStartup()
    }
    Spacer(Modifier.height(16.dp))
    StaticButton(stringResource(R.string.task_sync_now), true) { model.synchronize() }
    if (model.syncUnavailable) Text(stringResource(R.string.task_sync_unavailable))
    if (model.failed) Text(stringResource(R.string.task_local_error))
}

@Composable
internal fun BackgroundSettings(model: TaskViewModel, onRequestNotifications: () -> Unit) {
    Text(stringResource(R.string.task_background_settings), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(if (model.notificationsEnabled) R.string.task_notifications_on else R.string.task_notifications_off))
    if (!model.notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
        StaticButton(stringResource(R.string.task_notifications_request), true, onRequestNotifications)
    }
    if (model.backgroundRestricted) Text(stringResource(R.string.task_background_restricted))
    Text(stringResource(if (model.batteryOptimized) R.string.task_battery_optimized else R.string.task_battery_exempt))
    if (model.platformBlocked) {
        Text(stringResource(R.string.task_platform_waiting))
        StaticButton(stringResource(R.string.task_background_retry), true) { model.retryBackground() }
    }
    if (model.failed) Text(stringResource(R.string.task_local_error))
}

internal fun taskTypeResource(request: TaskRequest): Int = when (request) {
    is TaskRequest.CandidateConfiguration -> R.string.task_type_candidate
    is TaskRequest.MetadataSync -> R.string.task_type_metadata
    is TaskRequest.FormatCopy -> R.string.task_type_copy
    is TaskRequest.FormatCheck -> R.string.task_type_check
    is TaskRequest.CoverLoad -> R.string.task_type_cover
    is TaskRequest.ReadStatusWrite -> R.string.task_type_write
}

internal fun waitingResource(reason: WaitingReason): Int = when (reason) {
    WaitingReason.NETWORK -> R.string.task_wait_network
    WaitingReason.THROTTLED -> R.string.task_wait_throttled
    WaitingReason.LOGIN -> R.string.task_wait_login
    WaitingReason.DIRECTORY_AUTHORIZATION -> R.string.task_wait_directory
    WaitingReason.DEPENDENCY -> R.string.task_wait_dependency
    WaitingReason.INACTIVE_LIBRARY -> R.string.task_wait_library
    WaitingReason.RECOVERY -> R.string.task_wait_recovery
}

internal fun stageResource(stage: TaskStage): Int = when (stage) {
    TaskStage.CANDIDATE_ACCESS -> R.string.task_stage_candidate
    TaskStage.METADATA_FETCH -> R.string.task_stage_fetch
    TaskStage.METADATA_IMPORT -> R.string.task_stage_import
    TaskStage.FORMAT_CHECK -> R.string.task_stage_check
    TaskStage.FORMAT_TRANSFER -> R.string.task_stage_transfer
    TaskStage.FORMAT_PUBLISH -> R.string.task_stage_publish
    TaskStage.COVER_TRANSFER -> R.string.task_stage_cover_transfer
    TaskStage.COVER_PUBLISH -> R.string.task_stage_cover_publish
    TaskStage.WRITE_SNAPSHOT -> R.string.task_stage_write_snapshot
    TaskStage.WRITE_PREPARE -> R.string.task_stage_write_prepare
    TaskStage.WRITE_COMMIT -> R.string.task_stage_write_commit
    TaskStage.WRITE_REFETCH -> R.string.task_stage_write_refetch
    TaskStage.WRITE_IMPORT -> R.string.task_stage_write_import
    TaskStage.RECOVERY_CHECK -> R.string.task_stage_recovery
}

internal fun taskErrorResource(error: TaskError): Int = when (error) {
    TaskError.InvalidColumn -> R.string.task_error_column
    is TaskError.BookIdentityChanged -> R.string.task_error_identity
    is TaskError.Source -> when (error.error.kind) {
        StorageErrorKind.NO_NETWORK -> R.string.task_error_network
        StorageErrorKind.THROTTLED -> R.string.task_error_throttled
        StorageErrorKind.LOGIN_REQUIRED -> R.string.task_error_login
        StorageErrorKind.AUTHORIZATION_EXPIRED -> R.string.task_error_permission
        StorageErrorKind.SOURCE_MISSING -> R.string.task_error_missing
        StorageErrorKind.INCOMPATIBLE_DATABASE -> R.string.task_error_incompatible
        StorageErrorKind.VERSION_CONFLICT -> R.string.task_error_version
        StorageErrorKind.INSUFFICIENT_SPACE -> R.string.task_error_space
        StorageErrorKind.CORRUPT_CONTENT -> R.string.task_error_corrupt
        StorageErrorKind.UNSUPPORTED_OPERATION -> R.string.task_error_unsupported
        StorageErrorKind.LOCAL_IO -> R.string.task_error_io
    }
}
