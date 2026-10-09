package io.github.chenxiex.calibrecloud.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.auth.LoginStatus
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.cache.CleanupKind
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler

/** Title, format line, an optional problem line and the buttons. */
private val DOWNLOAD_ROW_HEIGHT = 120.dp

/**
 * The OneDrive directory chooser of the add-library wizard (R08): one explicit task lists the entered
 * directory completely; the pages slice that stored result, as many directories per page as the space
 * holds, without a request. Choosing a directory records it as the addition's root; [onChosen] follows.
 */
@Composable
internal fun DirectoryPage(model: OneDriveLibraryViewModel, oneDrive: OneDriveAuthorizationViewModel, onChosen: () -> Unit) {
    val authorized = oneDrive.status == LoginStatus.AUTHORIZED && !oneDrive.busy
    val idle = authorized && !model.submitting
    val page = model.page
    LaunchedEffect(authorized) { if (authorized) model.browseIfEmpty() }
    val status = directoryStatusResource(
        model.record?.state,
        (model.record?.submission?.request as? TaskRequest.CandidateConfiguration)?.operation,
        model.rejected,
        model.submitting,
    ).takeUnless { model.loading || it == R.string.onedrive_browse_completed }
    Column(Modifier.fillMaxSize().testTag("directory_page")) {
        if (page == null) {
            // Before the first listing arrives (restoring or opening the root), only the loading icon shows.
            if (status == null || status == R.string.onedrive_task_pending) {
                Centered { LoadingIcon() }
                return@Column
            }
            FormColumn("directory_failure") {
                Text(stringResource(status), Modifier.testTag("directory_status"))
                if (model.rejected || model.record?.state is TaskState.Finished) {
                    ActionButton(stringResource(R.string.onedrive_browse), idle, Modifier.testTag("onedrive_browse"), ButtonKind.PRIMARY) {
                        model.browse()
                    }
                }
            }
            return@Column
        }
        // One row: the directory, then reloading it, going up and choosing it, as in a system folder picker.
        Row(Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(start = PAGE_MARGIN), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.onedrive_current_directory, page.directoryName.ifEmpty { stringResource(R.string.onedrive_root_name) }),
                Modifier.weight(1f).testTag("directory_current"), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.loading) IconSlot(R.drawable.ic_hourglass, stringResource(R.string.onedrive_loading), Modifier.testTag("directory_loading"))
            IconAction(R.drawable.ic_refresh, stringResource(R.string.onedrive_directory_reload), idle,
                Modifier.testTag("onedrive_directory_reload")) { model.reload() }
            IconAction(R.drawable.ic_arrow_up, stringResource(R.string.onedrive_directory_up), idle && model.canGoUp,
                Modifier.testTag("onedrive_directory_up")) { model.up() }
            IconAction(R.drawable.ic_check, stringResource(R.string.onedrive_directory_choose), idle,
                Modifier.testTag("onedrive_directory_choose")) { model.choose(page.parentItemId, onChosen) }
        }
        status?.let {
            Text(stringResource(it), Modifier.padding(horizontal = PAGE_MARGIN).padding(bottom = TIGHT_GAP).testTag("directory_status"),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalRule()
        PagedArea(page.page, model.pageCount, "directory", model::paginate, Modifier.weight(1f).fillMaxWidth(), showBar = true) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val rows = listGeometry(maxWidth.value, maxHeight.value, ROW_HEIGHT.value).rows
                LaunchedEffect(rows) { model.onMeasured(rows) }
                if (page.items.isEmpty()) EmptyMessage(stringResource(R.string.onedrive_directory_empty))
                Column(Modifier.fillMaxSize()) {
                    page.items.take(rows).forEach { item ->
                        ListItem(item.name, Modifier.testTag("directory_${item.id}"), enabled = idle, onClick = { model.enter(item.id) }) {
                            IconSlot(R.drawable.ic_next_page, null, enabled = idle)
                        }
                    }
                }
            }
        }
    }
}

/** A still hourglass: e-ink pages show waiting without animation. */
@Composable
private fun LoadingIcon() {
    Icon(painterResource(R.drawable.ic_hourglass), stringResource(R.string.onedrive_loading), Modifier.size(ICON_SIZE).testTag("directory_loading"),
        tint = INK)
}

/** Shared text mapping keeps browser errors visible beside the control that submitted them. */
internal fun oneDriveTaskStatusResource(taskState: TaskState?, rejected: Boolean): Int {
    return when (taskState) {
        TaskState.Queued -> R.string.onedrive_task_queued
        is TaskState.Running -> R.string.onedrive_task_running
        is TaskState.Waiting -> when {
            WaitingReason.NETWORK in taskState.reasons -> R.string.onedrive_task_network
            WaitingReason.THROTTLED in taskState.reasons -> R.string.task_wait_throttled
            WaitingReason.LOGIN in taskState.reasons -> R.string.onedrive_relogin
            else -> R.string.onedrive_task_waiting
        }
        is TaskState.Paused -> R.string.onedrive_task_paused
        is TaskState.Finished -> when (val result = taskState.result) {
            TaskResult.Completed -> R.string.onedrive_browse_completed
            is TaskResult.Cancelled -> R.string.onedrive_task_cancelled
            is TaskResult.Failed -> when ((result.failure.error as? TaskError.Source)?.error?.kind) {
                StorageErrorKind.NO_NETWORK -> R.string.onedrive_task_network_failed
                StorageErrorKind.THROTTLED -> R.string.task_error_throttled
                StorageErrorKind.LOGIN_REQUIRED -> R.string.onedrive_relogin
                StorageErrorKind.AUTHORIZATION_EXPIRED -> R.string.onedrive_task_permission
                StorageErrorKind.SOURCE_MISSING -> R.string.onedrive_task_missing
                StorageErrorKind.UNSUPPORTED_OPERATION -> R.string.onedrive_task_unsupported
                else -> R.string.onedrive_task_io
            }
            else -> R.string.onedrive_task_io
        }
        null -> if (rejected) R.string.onedrive_task_rejected else R.string.onedrive_task_pending
    }
}

/** Sync tasks belong to the sync status and cannot stand in for directory results. */
internal fun directoryStatusResource(taskState: TaskState?, operation: String?, rejected: Boolean, submitting: Boolean): Int {
    if (rejected) return R.string.onedrive_task_rejected
    if (operation == TaskRequest.CandidateConfiguration.LIBRARY_SYNC) return R.string.onedrive_task_pending
    if (submitting && (taskState == null || taskState is TaskState.Finished)) return R.string.onedrive_task_queued
    return oneDriveTaskStatusResource(if (operation == OneDriveCandidateTaskHandler.BROWSE) taskState else null, false)
}

/**
 * "已下载文件" (R12): the manifest's complete copies, usable without a full import, as many per page as
 * fit. Each can be opened or removed (this format or every format of the book) after a confirmation
 * that shows the exact range.
 */
@Composable
internal fun DownloadsPage(model: DownloadViewModel, onOpen: (CopyKey, String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(model) { model.restore() }
    Box(Modifier.fillMaxSize().testTag("downloads_page")) {
        Column(Modifier.fillMaxSize()) {
            model.removalResult?.let {
                Text(stringResource(if (it) R.string.download_remove_done else R.string.download_remove_failed),
                    Modifier.padding(horizontal = PAGE_MARGIN, vertical = SECTION_GAP).testTag("download_result"))
            }
            when {
                model.readFailed -> EmptyMessage(stringResource(R.string.download_local_error))
                model.selection?.identity == null -> EmptyMessage(stringResource(R.string.download_no_library))
                else -> {
                    val capacity = model.capacity.coerceAtLeast(1)
                    PagedArea(model.offset / capacity, pageCount(model.total, capacity), "downloads", model::showPage,
                        Modifier.weight(1f).fillMaxWidth(), showBar = true) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val geometry = listGeometry(maxWidth.value, maxHeight.value, DOWNLOAD_ROW_HEIGHT.value)
                            LaunchedEffect(geometry.rows) { model.onMeasured(geometry.rows) }
                            if (model.total == 0) EmptyMessage(stringResource(R.string.download_list_empty), Modifier.testTag("download_empty"))
                            Column(Modifier.fillMaxSize()) {
                                model.entries.take(geometry.rows).forEach { entry ->
                                    DownloadRow(entry, geometry.cellHeight.dp, !model.submitting, onOpen, model::previewRemoval)
                                }
                            }
                        }
                    }
                }
            }
        }
        model.removal?.let { plan ->
            ConfirmPanel("download_remove_dialog", !model.submitting, model::cancelRemoval, model::confirmRemoval) {
                Text(if (plan.formats == null) stringResource(R.string.download_remove_all_scope)
                    else stringResource(R.string.removal_formats, plan.formats.map { it.value }.sorted().joinToString(stringResource(R.string.removal_format_separator))))
                Text(if (plan.copies.isEmpty()) stringResource(R.string.removal_no_copies)
                    else pluralStringResource(R.plurals.removal_copies, plan.copies.size, plan.copies.size, Formatter.formatShortFileSize(context, plan.bytes)))
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadListEntry, height: androidx.compose.ui.unit.Dp, enabled: Boolean,
    onOpen: (CopyKey, String) -> Unit, onRemove: (DownloadListEntry, Boolean) -> Unit,
) {
    val copy = entry.copy
    val tag = "${copy.key.book.sourceId}_${copy.key.format.value}"
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().height(height).testTag("download_$tag")) {
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = PAGE_MARGIN, vertical = TIGHT_GAP), verticalArrangement = Arrangement.SpaceBetween) {
            Text(copy.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = copy.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
            SupportingText(
                listOfNotNull(copy.key.format.value, size, stringResource(when (copy.sourceAvailability) {
                    SourceAvailability.UNCONFIRMED -> R.string.download_source_unconfirmed
                    SourceAvailability.AVAILABLE -> R.string.download_source_available
                    SourceAvailability.CONFIRMED_MISSING -> R.string.download_source_missing
                })).joinToString(stringResource(R.string.list_separator)),
            )
            if (entry.status != DownloadCopyStatus.AVAILABLE) {
                SupportingText(stringResource(if (entry.status == DownloadCopyStatus.MISSING) R.string.download_copy_missing else R.string.download_copy_failed))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                ActionButton(stringResource(R.string.download_open), enabled && entry.status == DownloadCopyStatus.AVAILABLE,
                    Modifier.testTag("download_open_$tag")) { onOpen(copy.key, copy.title) }
                ActionButton(stringResource(R.string.download_remove), enabled, Modifier.testTag("download_remove_$tag")) { onRemove(entry, false) }
                ActionButton(stringResource(R.string.download_remove_all), enabled, Modifier.testTag("download_remove_all_$tag")) { onRemove(entry, true) }
            }
        }
        InsetRule()
    }
}

/**
 * Clearing the metadata cache or other libraries' caches (R12, R03): entering the page computes the
 * exact local range without touching a source; only the confirmation executes that same plan.
 */
@Composable
internal fun CleanupPage(model: CleanupViewModel, kind: CleanupKind) = FormColumn("cleanup_page") {
    val context = LocalContext.current
    LaunchedEffect(kind) { model.preview(kind) }
    Text(stringResource(if (kind == CleanupKind.METADATA) R.string.cleanup_metadata_scope else R.string.cleanup_other_libraries_scope))
    val plan = model.plan?.takeIf { it.kind == kind }
    if (plan != null) {
        Text(stringResource(R.string.cleanup_range,
            pluralStringResource(R.plurals.cleanup_libraries, plan.libraries.size, plan.libraries.size),
            pluralStringResource(R.plurals.cleanup_copies, plan.copies.size, plan.copies.size), Formatter.formatShortFileSize(context, plan.bytes)),
            Modifier.testTag("cleanup_range"))
        Row(horizontalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
            ActionButton(stringResource(R.string.cleanup_cancel), !model.busy, Modifier.testTag("cleanup_cancel"), ButtonKind.TEXT) { model.cancel() }
            ActionButton(stringResource(R.string.cleanup_confirm), !model.busy, Modifier.testTag("cleanup_confirm"), ButtonKind.PRIMARY) { model.confirm() }
        }
    } else if (!model.busy) {
        ActionButton(stringResource(R.string.cleanup_review), true, Modifier.testTag("cleanup_review")) { model.preview(kind) }
    }
    if (model.busy) Text(stringResource(R.string.cleanup_busy))
    model.result?.let { result ->
        Text(stringResource(when (result) {
            CleanupResult.COMPLETED -> R.string.cleanup_completed
            CleanupResult.FAILED -> R.string.cleanup_failed
            CleanupResult.UNAVAILABLE -> R.string.cleanup_unavailable
        }), Modifier.testTag("cleanup_result"))
    }
}

/**
 * Format priority (R24): the saved order, then the imported formats it does not name, by name. Up and
 * down move a format one place and save at once; the library resolves default formats with it.
 */
@Composable
internal fun FormatPriorityPage(model: MetadataViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var moved by remember { mutableStateOf<Int?>(null) }
    val formats = model.formats
    val entries = buildList {
        add(MenuEntry.Note("format_explanation", stringResource(R.string.format_explanation)))
        if (model.configurationFailed) add(MenuEntry.Note("format_failed", stringResource(R.string.format_failed)))
        formats.forEachIndexed { index, format ->
            add(MenuEntry.Ordered("format_${format.value}", stringResource(R.string.format_position, index + 1, format.value),
                stringResource(R.string.format_move_up, format.value), stringResource(R.string.format_move_down, format.value),
                !model.busy && index > 0, !model.busy && index < formats.lastIndex,
                { moved = index - 1; model.moveFormat(format, true) }, { moved = index + 1; model.moveFormat(format, false) }))
        }
    }
    val offset = entries.size - formats.size
    PagedEntries(entries, page, { page = it }, Modifier.testTag("format_page"), "format", focus = moved?.let { it + offset })
}

/** The read column (R13): the imported boolean columns; the choice is saved privately, never in the source. */
@Composable
internal fun ReadColumnPage(model: MetadataViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val imported = model.imported
    val entries = buildList {
        add(MenuEntry.Note("read_column_state", when {
            model.readFailed -> stringResource(R.string.metadata_local_error)
            imported == null -> stringResource(R.string.read_column_no_import)
            imported.readColumnStatus == ReadColumnStatus.VALID -> stringResource(R.string.metadata_columns_explanation)
            imported.readColumnStatus == ReadColumnStatus.INVALID -> stringResource(R.string.metadata_columns_invalid)
            else -> stringResource(R.string.metadata_columns_unconfigured)
        }))
        if (model.configurationFailed) add(MenuEntry.Note("read_column_failed", stringResource(R.string.metadata_configuration_failed)))
        if (imported != null) {
            val columns = imported.metadata.columns.filter { it.datatype == "bool" && it.supported }
            if (columns.isEmpty()) add(MenuEntry.Note("read_column_empty", stringResource(R.string.metadata_columns_empty)))
            columns.forEach { column ->
                add(MenuEntry.Choice("read_column_${column.id.sourceId}",
                    stringResource(R.string.metadata_column_option, column.name, column.id.lookupName),
                    selected = column.id == imported.selectedReadColumn,
                    enabled = !model.busy) { model.selectColumn(column.id) })
            }
            add(MenuEntry.Rule)
            add(MenuEntry.Choice("read_column_none", stringResource(R.string.metadata_columns_clear),
                selected = imported.selectedReadColumn == null,
                enabled = !model.busy) { model.selectColumn(null) })
        }
    }
    PagedEntries(entries, page, { page = it }, Modifier.testTag("read_column_page"), "read_column")
}
