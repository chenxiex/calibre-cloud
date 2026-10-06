package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl

/** One explicit format per page; no source request is made while choosing a page. */
@Composable
internal fun DownloadControls(model: DownloadViewModel) {
    Text(stringResource(R.string.download_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.download_restart_notice), maxLines = 2, overflow = TextOverflow.Ellipsis)
    var page by rememberSaveable(model.selection?.token?.toString()) { mutableIntStateOf(0) }
    val currentPage = page.coerceIn(0, maxOf(0, model.options.lastIndex))
    val option = model.options.getOrNull(currentPage)
    if (model.readFailed) {
        Text(stringResource(R.string.download_local_error))
    } else if (model.selection?.identity == null) {
        Text(stringResource(R.string.metadata_unvalidated))
    } else if (option == null) {
        Text(stringResource(R.string.download_formats_empty))
    } else {
        Spacer(Modifier.height(8.dp))
        Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (option.sizeBytes == null) stringResource(R.string.download_format, option.key.format.value)
            else stringResource(R.string.download_format_size, option.key.format.value, option.sizeBytes),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        StaticButton(stringResource(if (model.selection?.backend == BackendKind.LOCAL)
            R.string.download_copy_local else R.string.download_fetch), !model.submitting) { model.download(option) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.download_format_page, currentPage + 1, model.options.size))
        Row {
            StaticButton(stringResource(R.string.page_previous), currentPage > 0) { page = currentPage - 1 }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.page_next), currentPage + 1 < model.options.size) { page = currentPage + 1 }
        }
    }
    Spacer(Modifier.height(8.dp))
    (model.record?.submission?.request as? TaskRequest.FormatCopy)?.let { task ->
        Text(stringResource(R.string.download_task_format, task.resource.book.sourceId, task.resource.format.value),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(stringResource(downloadTaskStatusResource(model.record?.state, model.rejected)), maxLines = 2,
        overflow = TextOverflow.Ellipsis)
    model.record?.controls?.let { controls ->
        Row {
            if (controls.canPause) StaticButton(stringResource(R.string.local_snapshot_pause), true) { model.control(TaskControl.PAUSE) }
            if (controls.canResume) StaticButton(stringResource(R.string.local_snapshot_resume), !model.submitting) { model.control(TaskControl.RESUME) }
            if (controls.canRetry) StaticButton(stringResource(R.string.local_snapshot_retry), !model.submitting) { model.control(TaskControl.RETRY) }
            if (controls.canCancel) {
                Spacer(Modifier.width(8.dp))
                StaticButton(stringResource(R.string.onedrive_task_cancel), true) { model.control(TaskControl.CANCEL) }
            }
            if (model.record?.state == TaskState.Queued || model.record?.state is TaskState.Waiting) {
                Spacer(Modifier.width(8.dp))
                StaticButton(stringResource(R.string.local_snapshot_run), !model.submitting) { model.runQueued() }
            }
        }
    }
}

/** Uses the complete manifest even when the full imported metadata is unavailable. */
@Composable
internal fun DownloadList(model: DownloadViewModel) {
    Text(stringResource(R.string.download_list_title), style = MaterialTheme.typography.titleMedium)
    if (model.readFailed) {
        Text(stringResource(R.string.download_local_error))
    } else if (model.selection?.identity == null) {
        Text(stringResource(R.string.metadata_unvalidated))
    } else {
        if (model.entries.isEmpty()) Text(stringResource(R.string.download_list_empty))
        model.entries.forEach { entry ->
            Spacer(Modifier.height(8.dp))
            Column {
                Text(entry.copy.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.download_format, entry.copy.key.format.value))
                entry.copy.sizeBytes?.let { Text(stringResource(R.string.download_size, it)) }
                Text(stringResource(when (entry.copy.sourceAvailability) {
                    SourceAvailability.UNCONFIRMED -> R.string.download_source_unconfirmed
                    SourceAvailability.AVAILABLE -> R.string.download_source_available
                    SourceAvailability.CONFIRMED_MISSING -> R.string.download_source_missing
                }))
                Text(stringResource(when (entry.status) {
                    DownloadCopyStatus.AVAILABLE -> R.string.download_copy_available
                    DownloadCopyStatus.MISSING -> R.string.download_copy_missing
                    DownloadCopyStatus.FAILED -> R.string.download_copy_failed
                }))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.download_list_page, model.listPage + 1))
        Row {
            StaticButton(stringResource(R.string.page_previous), model.listPage > 0) { model.paginate(model.listPage - 1) }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.page_next), model.listHasNext) { model.paginate(model.listPage + 1) }
        }
    }
    Spacer(Modifier.height(8.dp))
    StaticButton(stringResource(R.string.metadata_refresh_local), true) { model.restore() }
}

internal fun downloadTaskStatusResource(state: TaskState?, rejected: Boolean): Int {
    if (rejected) return R.string.download_rejected
    return when (state) {
        null -> R.string.download_pending
        TaskState.Queued -> R.string.download_queued
        is TaskState.Running -> R.string.download_running
        is TaskState.Waiting -> when {
            WaitingReason.NETWORK in state.reasons -> R.string.onedrive_task_network
            WaitingReason.LOGIN in state.reasons -> R.string.onedrive_relogin
            else -> R.string.download_waiting
        }
        is TaskState.Paused -> R.string.download_paused
        is TaskState.Finished -> when (val result = state.result) {
            TaskResult.Completed -> R.string.download_complete
            is TaskResult.Cancelled -> R.string.download_cancelled
            is TaskResult.Failed -> when ((result.failure.error as? TaskError.Source)?.error?.kind) {
                StorageErrorKind.NO_NETWORK -> R.string.onedrive_task_network
                StorageErrorKind.LOGIN_REQUIRED -> R.string.onedrive_relogin
                StorageErrorKind.AUTHORIZATION_EXPIRED -> R.string.download_permission
                StorageErrorKind.SOURCE_MISSING -> R.string.download_missing
                StorageErrorKind.VERSION_CONFLICT -> R.string.download_conflict
                StorageErrorKind.INSUFFICIENT_SPACE -> R.string.local_snapshot_space
                StorageErrorKind.CORRUPT_CONTENT -> R.string.download_corrupt
                else -> R.string.download_io
            }
            else -> R.string.download_io
        }
    }
}
