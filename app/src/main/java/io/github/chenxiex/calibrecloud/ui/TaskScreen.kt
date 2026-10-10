package io.github.chenxiex.calibrecloud.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl

/** Which side of the queue the page shows: unfinished work or finished results (including failures and cancels). */
internal enum class TaskTab { ACTIVE, FINISHED }

/** Who triggered the work; promoted tasks count as user requests. */
internal enum class TaskSourceFilter { ALL, USER, AUTOMATIC }

/** The task page polls only while shown; leaving it stops the two-second refresh. */
@Composable
internal fun TaskScreen(model: TaskViewModel) {
    DisposableEffect(model) {
        model.setVisible(true)
        onDispose { model.setVisible(false) }
    }
    TaskList(model.records, model.failed, model.operationFailed, model.writeBooks, model::control)
}

/**
 * The durable queue (R18, Q62) as a paged list under two tabs and a source filter, as many flat rows per
 * page as fit: a short type title, a line of small status text (a pin-to-top arrow marks user requests,
 * the scheduling position while it only waits its turn, otherwise stage and percentage, waiting reasons or
 * result), and on the right only the control icons its state supports, or a check once completed.
 * Automatic cover batches of the shown tab fold into one row by default (R18); tapping it shows them.
 * A read-state write's title counts the books of its change list ([writeBooks]).
 */
@Composable
internal fun TaskList(records: List<TaskRecord>, failed: Boolean, operationFailed: Boolean = false, writeBooks: Map<TaskId, Int> = emptyMap(),
    onControl: (TaskRecord, TaskControl) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableStateOf(TaskTab.ACTIVE) }
    var source by rememberSaveable { mutableStateOf(TaskSourceFilter.ALL) }
    var coversExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("task_page")) {
        if (failed) Text(stringResource(R.string.task_local_error), Modifier.padding(horizontal = PAGE_MARGIN, vertical = TIGHT_GAP))
        if (operationFailed) Text(stringResource(R.string.task_control_error), Modifier.padding(horizontal = PAGE_MARGIN, vertical = TIGHT_GAP))
        // The tabs, then the source filter at the end; the chosen chip of each is filled (Q65).
        Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN), horizontalArrangement = Arrangement.spacedBy(TIGHT_GAP),
            verticalAlignment = Alignment.CenterVertically) {
            TaskTab.entries.forEach { entry ->
                ChoiceChip(stringResource(if (entry == TaskTab.ACTIVE) R.string.task_tab_active else R.string.task_tab_finished),
                    entry == tab, Modifier.testTag("task_tab_${entry.name.lowercase()}"), Role.Tab) { tab = entry; page = 0 }
            }
            Spacer(Modifier.weight(1f))
            TaskSourceFilter.entries.forEach { entry ->
                ChoiceChip(stringResource(when (entry) {
                    TaskSourceFilter.ALL -> R.string.task_filter_all
                    TaskSourceFilter.USER -> R.string.task_filter_user
                    TaskSourceFilter.AUTOMATIC -> R.string.task_filter_automatic
                }), entry == source, Modifier.testTag("task_filter_${entry.name.lowercase()}"), Role.Tab) { source = entry; page = 0 }
            }
        }
        HorizontalRule()
        // Only tasks that wait their turn have a scheduling position, counted over the whole queue.
        val pending = records.filter { it.state == TaskState.Queued }
        val shown = records.filter { record ->
            (record.state is TaskState.Finished) == (tab == TaskTab.FINISHED) && when (source) {
                TaskSourceFilter.ALL -> true
                TaskSourceFilter.USER -> record.effectiveOrigin.priority == TaskPriority.HIGH
                TaskSourceFilter.AUTOMATIC -> record.effectiveOrigin.priority == TaskPriority.LOW
            }
        }.let { filtered ->
            // No finish time is kept: the latest submitted (or promoted) finished task comes first (Q63).
            if (tab == TaskTab.FINISHED) filtered.sortedByDescending { it.scheduling.sequence.value } else filtered
        }
        if (shown.isEmpty()) {
            EmptyMessage(stringResource(R.string.task_empty), Modifier.testTag("task_empty"))
            return@Column
        }
        val lines = foldCoverBatches(shown, coversExpanded)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val whole = listGeometry(maxWidth.value, maxHeight.value, TWO_LINE_ROW_HEIGHT.value).rows
            val rows = if (lines.size <= whole) whole
                else listGeometry(maxWidth.value, (maxHeight - PAGE_BAR_HEIGHT).value, TWO_LINE_ROW_HEIGHT.value).rows
            val pages = pageCount(lines.size, rows)
            val current = page.coerceIn(0, pages - 1)
            PagedArea(current, pages, "tasks", { page = it }, Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    lines.drop(current * rows).take(rows).forEach { line ->
                        when (line) {
                            is TaskLine.Single -> TaskRow(line.record, pending.indexOf(line.record).takeIf { it >= 0 }?.plus(1), writeBooks[line.record.id], onControl)
                            is TaskLine.CoverGroup -> CoverGroupRow(line, coversExpanded,
                                pending.indexOf(line.shown).takeIf { it >= 0 }?.plus(1)) { coversExpanded = !coversExpanded }
                        }
                    }
                }
            }
        }
    }
}

/** A row of the task list: one task, or the folded automatic cover batches of the tab. */
internal sealed interface TaskLine {
    data class Single(val record: TaskRecord) : TaskLine
    /** [shown] is the batch whose state the row repeats: the running one, otherwise the first listed. */
    data class CoverGroup(val batches: List<TaskRecord>, val shown: TaskRecord) : TaskLine
}

private fun TaskRecord.automaticCovers() = submission.request is TaskRequest.CoverLoad && effectiveOrigin.priority == TaskPriority.LOW

/** Two or more automatic cover batches share one row where the first of them is; expanded, they follow it. */
internal fun foldCoverBatches(records: List<TaskRecord>, expanded: Boolean): List<TaskLine> {
    val batches = records.filter { it.automaticCovers() }
    if (batches.size < 2) return records.map { TaskLine.Single(it) }
    val group = TaskLine.CoverGroup(batches, batches.firstOrNull { it.state is TaskState.Running } ?: batches.first())
    return buildList {
        records.forEach { record ->
            if (!record.automaticCovers()) add(TaskLine.Single(record))
            else if (record == batches.first()) {
                add(group)
                if (expanded) batches.forEach { add(TaskLine.Single(it)) }
            }
        }
    }
}

@Composable
private fun CoverGroupRow(group: TaskLine.CoverGroup, expanded: Boolean, position: Int?, onToggle: () -> Unit) {
    ListItem(
        stringResource(R.string.task_type_cover), Modifier.testTag("task_cover_group"), TWO_LINE_ROW_HEIGHT,
        bold = true, divider = true, onClick = onToggle,
        supporting = {
            SupportingText(listOf(pluralStringResource(R.plurals.task_cover_batches, group.batches.size, group.batches.size),
                taskStatusLine(group.shown, position)).joinToString(stringResource(R.string.task_status_separator)),
                Modifier.testTag("task_status"))
        },
    ) {
        IconSlot(if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
            stringResource(if (expanded) R.string.task_collapse else R.string.task_expand), Modifier.testTag("task_cover_toggle"))
    }
}

@Composable
private fun TaskRow(record: TaskRecord, position: Int?, books: Int?, onControl: (TaskRecord, TaskControl) -> Unit) {
    val type = stringResource(taskTypeResource(record.submission.request))
    ListItem(
        if (books != null && books > 0) pluralStringResource(R.plurals.task_type_write_books, books, type, books) else type,
        Modifier.testTag("task_${record.id.value}"), TWO_LINE_ROW_HEIGHT,
        bold = true, divider = true,
        supporting = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (record.effectiveOrigin.priority == TaskPriority.HIGH) {
                    Icon(painterResource(R.drawable.ic_pin_top), stringResource(R.string.task_filter_user),
                        Modifier.padding(end = TIGHT_GAP).size(USER_MARK_SIZE).testTag("task_user_mark"), tint = INK)
                }
                SupportingText(taskStatusLine(record, position), Modifier.testTag("task_status"))
            }
        },
    ) {
        val controls = record.controls
        val result = (record.state as? TaskState.Finished)?.result
        if (controls.canPause) IconAction(R.drawable.ic_pause, stringResource(R.string.task_pause), true, Modifier.testTag("task_pause")) { onControl(record, TaskControl.PAUSE) }
        if (controls.canResume) IconAction(R.drawable.ic_play, stringResource(R.string.task_resume), true, Modifier.testTag("task_resume")) { onControl(record, TaskControl.RESUME) }
        if (controls.canRetry) IconAction(R.drawable.ic_refresh, stringResource(R.string.task_retry), true, Modifier.testTag("task_retry")) { onControl(record, TaskControl.RETRY) }
        if (controls.canCancel) IconAction(R.drawable.ic_close, stringResource(R.string.task_cancel), true, Modifier.testTag("task_cancel")) { onControl(record, TaskControl.CANCEL) }
        if (!controls.canRetry && (result == TaskResult.Completed || result is TaskResult.CompletedWithBookFailures)) {
            IconSlot(R.drawable.ic_check, stringResource(R.string.task_completed), Modifier.testTag("task_done"))
        }
    }
}

/** The pin-to-top arrow of user requests fits the supporting line. */
private val USER_MARK_SIZE = 14.dp

/** A task that only waits its turn shows "排队" and its position (R18); other states a short stage, percentage or reason. */
@Composable
private fun taskStatusLine(record: TaskRecord, position: Int?): String = buildList {
    val write = record.submission.request is TaskRequest.ReadStatusWrite
    when (val state = record.state) {
        TaskState.Queued -> add(if (position != null) stringResource(R.string.task_queued_position, position) else stringResource(R.string.task_queued))
        is TaskState.Waiting -> add(state.reasons.map { stringResource(waitingResource(it)) }.joinToString(stringResource(R.string.list_separator)))
        is TaskState.Running -> {
            add(stringResource(stageResource(state.stage)))
            val progress = state.progress
            val total = progress?.total
            if (progress != null && total != null && total > 0) {
                add(stringResource(R.string.task_progress, (progress.completed * 100 / total).toInt().coerceIn(0, 100)))
            }
            if (state.stage == TaskStage.WRITE_COMMIT) add(stringResource(R.string.task_committing))
        }
        is TaskState.Paused -> {
            add(stringResource(R.string.task_paused))
            add(stringResource(stageResource(state.stage)))
        }
        is TaskState.Finished -> when (val result = state.result) {
            TaskResult.Completed -> add(stringResource(if (write) R.string.task_written else R.string.task_completed))
            is TaskResult.CompletedWithBookFailures -> add(pluralStringResource(when (record.submission.request) {
                is TaskRequest.CoverLoad -> R.plurals.task_partial_cover
                is TaskRequest.ReadStatusWrite -> R.plurals.task_partial_write
                else -> R.plurals.task_partial
            }, result.failures.size, result.failures.size))
            TaskResult.Cancelled -> add(stringResource(R.string.task_cancelled))
            is TaskResult.Failed -> {
                val error = result.failure.error
                // A conflicting push only fails once the automatic rounds are used up (R15).
                val reason = if (write && (error as? TaskError.Source)?.error?.kind == StorageErrorKind.VERSION_CONFLICT) {
                    stringResource(R.string.task_error_version_retried, TaskCoordinator.MAX_RETRIES)
                } else stringResource(taskErrorResource(error))
                add(stringResource(R.string.task_failed, stringResource(stageResource(result.failure.stage)), reason))
            }
        }
    }
    if (record.restartedTransfer && (record.state is TaskState.Running || record.state is TaskState.Paused)) {
        add(stringResource(R.string.task_transfer_restarted))
    }
}.joinToString(stringResource(R.string.task_status_separator))

/** Notification permission and the platform's background limits, which can delay queued work. */
@Composable
internal fun BackgroundSettings(model: TaskViewModel, onRequestNotifications: () -> Unit) = FormColumn("background_page") {
    LaunchedEffect(model) { model.refreshCapabilities() }
    Text(stringResource(if (model.notificationsEnabled) R.string.task_notifications_on else R.string.task_notifications_off))
    if (!model.notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
        ActionButton(stringResource(R.string.task_notifications_request), true, kind = ButtonKind.PRIMARY) { onRequestNotifications() }
    }
    if (model.backgroundRestricted) Text(stringResource(R.string.task_background_restricted))
    Text(stringResource(if (model.batteryOptimized) R.string.task_battery_optimized else R.string.task_battery_exempt))
    if (model.platformBlocked) {
        Text(stringResource(R.string.task_platform_waiting))
        ActionButton(stringResource(R.string.task_background_retry), true) { model.retryBackground() }
    }
    if (model.failed) Text(stringResource(R.string.task_local_error))
}

internal fun taskTypeResource(request: TaskRequest): Int = when (request) {
    is TaskRequest.CandidateConfiguration -> when (request.operation) {
        TaskRequest.CandidateConfiguration.LIBRARY_SYNC -> R.string.task_type_metadata
        OneDriveCandidateTaskHandler.BROWSE -> R.string.task_type_directory
        else -> R.string.task_type_candidate
    }
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
}

internal fun stageResource(stage: TaskStage): Int = when (stage) {
    TaskStage.CANDIDATE_ACCESS -> R.string.task_stage_candidate
    TaskStage.FORMAT_CHECK -> R.string.task_stage_check
    TaskStage.FORMAT_TRANSFER -> R.string.task_stage_transfer
    TaskStage.FORMAT_PUBLISH -> R.string.task_stage_publish
    TaskStage.COVER_TRANSFER -> R.string.task_stage_cover_transfer
    TaskStage.COVER_PUBLISH -> R.string.task_stage_cover_publish
    TaskStage.WRITE_SNAPSHOT -> R.string.task_stage_write_snapshot
    TaskStage.WRITE_PREPARE -> R.string.task_stage_write_prepare
    TaskStage.WRITE_COMMIT -> R.string.task_stage_write_commit
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
        StorageErrorKind.LEFTOVER_FILES -> R.string.task_error_leftover
    }
}
