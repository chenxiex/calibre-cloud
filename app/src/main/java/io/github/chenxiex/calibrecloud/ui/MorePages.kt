package io.github.chenxiex.calibrecloud.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.auth.LoginIssue
import io.github.chenxiex.calibrecloud.auth.LoginStatus
import io.github.chenxiex.calibrecloud.auth.OneDriveOAuthConfiguration
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.cache.CleanupKind
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus
import io.github.chenxiex.calibrecloud.storage.local.DirectorySelectionIssue
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler

private val PAGE_ROW_HEIGHT = 48.dp
private val DIRECTORY_ROW_HEIGHT = 52.dp
private val DOWNLOAD_ROW_HEIGHT = 124.dp

/** A short sub-page: text and buttons from the top, never scrolled. */
@Composable
private fun PageColumn(tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIcon() }
                return@Column
            }
            Text(stringResource(status), Modifier.padding(16.dp).testTag("directory_status"))
            if (model.rejected || model.record?.state is TaskState.Finished) {
                StaticButton(stringResource(R.string.onedrive_browse), idle, Modifier.padding(horizontal = 16.dp).testTag("onedrive_browse")) {
                    model.browse()
                }
            }
            return@Column
        }
        // One row: the directory, then reloading it, going up and choosing it, as in a system folder picker.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.onedrive_current_directory, page.directoryName.ifEmpty { stringResource(R.string.onedrive_root_name) }),
                Modifier.weight(1f).testTag("directory_current"), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.loading) LoadingIcon(Modifier.padding(horizontal = 12.dp))
            IconAction(R.drawable.ic_refresh, stringResource(R.string.onedrive_directory_reload), idle,
                Modifier.testTag("onedrive_directory_reload")) { model.reload() }
            IconAction(R.drawable.ic_arrow_up, stringResource(R.string.onedrive_directory_up), idle && model.canGoUp,
                Modifier.testTag("onedrive_directory_up")) { model.up() }
            IconAction(R.drawable.ic_check, stringResource(R.string.onedrive_directory_choose), idle,
                Modifier.testTag("onedrive_directory_choose")) { model.choose(page.parentItemId, onChosen) }
        }
        status?.let {
            Text(stringResource(it), Modifier.padding(horizontal = 16.dp).testTag("directory_status"), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalRule()
        PagedArea(page.page, model.pageCount, "directory", PAGE_ROW_HEIGHT, model::paginate, Modifier.weight(1f).fillMaxWidth(), showBar = true) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val rows = listGeometry(maxWidth.value, maxHeight.value, DIRECTORY_ROW_HEIGHT.value).rows
                LaunchedEffect(rows) { model.onMeasured(rows) }
                Column(Modifier.fillMaxSize()) {
                    if (page.items.isEmpty()) Text(stringResource(R.string.onedrive_directory_empty), Modifier.padding(16.dp))
                    page.items.take(rows).forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().height(DIRECTORY_ROW_HEIGHT).testTag("directory_${item.id}")
                                .clickable(remember { MutableInteractionSource() }, null, enabled = idle, role = Role.Button) { model.enter(item.id) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.name, Modifier.weight(1f), color = if (idle) Color.Black else DISABLED_TINT, fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(painterResource(R.drawable.ic_next_page), null, Modifier.size(20.dp), tint = if (idle) Color.Black else DISABLED_TINT)
                        }
                    }
                }
            }
        }
    }
}

/** A still hourglass: e-ink pages show waiting without animation. */
@Composable
private fun LoadingIcon(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_hourglass), stringResource(R.string.onedrive_loading), modifier.size(24.dp).testTag("directory_loading"))
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
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("download_result"))
            }
            when {
                model.readFailed -> Text(stringResource(R.string.download_local_error), Modifier.padding(16.dp))
                model.selection?.identity == null -> Text(stringResource(R.string.download_no_library), Modifier.padding(16.dp))
                else -> {
                    val capacity = model.capacity.coerceAtLeast(1)
                    PagedArea(model.offset / capacity, pageCount(model.total, capacity), "downloads", PAGE_ROW_HEIGHT, model::showPage,
                        Modifier.weight(1f).fillMaxWidth(), showBar = true) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val geometry = listGeometry(maxWidth.value, maxHeight.value, DOWNLOAD_ROW_HEIGHT.value)
                            LaunchedEffect(geometry.rows) { model.onMeasured(geometry.rows) }
                            Column(Modifier.fillMaxSize()) {
                                if (model.total == 0) Text(stringResource(R.string.download_list_empty), Modifier.padding(16.dp).testTag("download_empty"))
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
            ConfirmPanel("download_remove_dialog", listOf(
                if (plan.formats == null) stringResource(R.string.download_remove_all_scope)
                else stringResource(R.string.removal_formats, plan.formats.map { it.value }.sorted().joinToString(stringResource(R.string.removal_format_separator))),
                if (plan.copies.isEmpty()) stringResource(R.string.removal_no_copies)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.removal_copies, plan.copies.size, plan.copies.size,
                    Formatter.formatShortFileSize(context, plan.bytes)),
                stringResource(R.string.removal_scope_note),
            ), !model.submitting, model::cancelRemoval, model::confirmRemoval)
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
    Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp, vertical = 6.dp).testTag("download_$tag"),
        verticalArrangement = Arrangement.SpaceBetween) {
        Text(copy.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val size = copy.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
        Text(
            listOfNotNull(copy.key.format.value, size, stringResource(when (copy.sourceAvailability) {
                SourceAvailability.UNCONFIRMED -> R.string.download_source_unconfirmed
                SourceAvailability.AVAILABLE -> R.string.download_source_available
                SourceAvailability.CONFIRMED_MISSING -> R.string.download_source_missing
            })).joinToString(stringResource(R.string.list_separator)),
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (entry.status != DownloadCopyStatus.AVAILABLE) {
            Text(stringResource(if (entry.status == DownloadCopyStatus.MISSING) R.string.download_copy_missing else R.string.download_copy_failed),
                fontSize = 13.sp, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StaticButton(stringResource(R.string.download_open), enabled && entry.status == DownloadCopyStatus.AVAILABLE,
                Modifier.testTag("download_open_$tag")) { onOpen(copy.key, copy.title) }
            StaticButton(stringResource(R.string.download_remove), enabled, Modifier.testTag("download_remove_$tag")) { onRemove(entry, false) }
            StaticButton(stringResource(R.string.download_remove_all), enabled, Modifier.testTag("download_remove_all_$tag")) { onRemove(entry, true) }
        }
    }
}

/** A confirmation over the page: [lines] describe the exact range; nothing happens until confirmed. */
@Composable
internal fun ConfirmPanel(tag: String, lines: List<String>, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.86f).background(Color.White).border(2.dp, Color.Black)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            lines.forEach { Text(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                StaticButton(stringResource(R.string.removal_cancel), enabled, Modifier.testTag("${tag}_cancel"), onCancel)
                Spacer(Modifier.width(12.dp))
                StaticButton(stringResource(R.string.removal_confirm), enabled, Modifier.testTag("${tag}_confirm"), onConfirm)
            }
        }
    }
}

/**
 * Clearing the metadata cache or other libraries' caches (R12, R03): entering the page computes the
 * exact local range without touching a source; only the confirmation executes that same plan.
 */
@Composable
internal fun CleanupPage(model: CleanupViewModel, kind: CleanupKind) = PageColumn("cleanup_page") {
    val context = LocalContext.current
    LaunchedEffect(kind) { model.preview(kind) }
    Text(stringResource(if (kind == CleanupKind.METADATA) R.string.cleanup_metadata_scope else R.string.cleanup_other_libraries_scope))
    val plan = model.plan?.takeIf { it.kind == kind }
    if (plan != null) {
        Text(stringResource(R.string.cleanup_range, plan.libraries.size, plan.copies.size, Formatter.formatShortFileSize(context, plan.bytes)),
            Modifier.testTag("cleanup_range"))
        Row {
            StaticButton(stringResource(R.string.cleanup_cancel), !model.busy, Modifier.testTag("cleanup_cancel")) { model.cancel() }
            Spacer(Modifier.width(12.dp))
            StaticButton(stringResource(R.string.cleanup_confirm), !model.busy, Modifier.testTag("cleanup_confirm")) { model.confirm() }
        }
    } else if (!model.busy) {
        StaticButton(stringResource(R.string.cleanup_review), true, Modifier.testTag("cleanup_review")) { model.preview(kind) }
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
    val chosen = stringResource(R.string.library_menu_chosen)
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
                    mark = if (column.id == imported.selectedReadColumn) R.drawable.ic_check else null, markDescription = chosen,
                    enabled = !model.busy) { model.selectColumn(column.id) })
            }
            add(MenuEntry.Rule)
            add(MenuEntry.Choice("read_column_none", stringResource(R.string.metadata_columns_clear),
                mark = if (imported.selectedReadColumn == null) R.drawable.ic_check else null, markDescription = chosen,
                enabled = !model.busy) { model.selectColumn(null) })
        }
    }
    PagedEntries(entries, page, { page = it }, Modifier.testTag("read_column_page"), "read_column")
}
