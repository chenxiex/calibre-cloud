package io.github.chenxiex.calibrecloud.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.storage.cache.CleanupKind
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the library and notifications hand over to "更多" when they need configuration, sync or downloads.
 * Choosing a library, re-granting a directory and signing in again all happen on the 书库 page.
 */
internal object MoreTarget {
    const val LOCATION = 0
    const val LOCAL_AUTHORIZATION = 1
    const val ONEDRIVE_LOGIN = 2
    /** The menu itself, whose metadata group starts with the sync entry. */
    const val SYNC = 3
    const val DOWNLOAD_LIST = 4
    /** The task queue, where a failed read-state write can be retried. */
    const val TASKS = 5
}

/** A page of "更多": the grouped menu or one of its sub-pages; back returns to [parent]. */
internal enum class MorePage(val parent: MorePage?, val title: Int) {
    MENU(null, R.string.more_title),
    LIBRARIES(MENU, R.string.more_libraries),
    ADD_LIBRARY(LIBRARIES, R.string.libraries_add),
    DOWNLOADS(MENU, R.string.more_downloads),
    CLEAR_METADATA(MENU, R.string.more_clear_metadata),
    CLEAR_OTHERS(MENU, R.string.more_clear_others),
    TASKS(MENU, R.string.more_tasks),
    BACKGROUND(MENU, R.string.more_background),
    FORMATS(MENU, R.string.more_formats),
    READ_COLUMN(MENU, R.string.more_read_column),
    ABOUT(MENU, R.string.more_about),
    PROJECT_LICENSE(ABOUT, R.string.about_project_license),
    NOTICES(ABOUT, R.string.about_notices),
    NOTICE_LICENSE(NOTICES, R.string.about_license_text);

    companion object {
        fun of(target: Int): MorePage = when (target) {
            MoreTarget.LOCATION, MoreTarget.LOCAL_AUTHORIZATION, MoreTarget.ONEDRIVE_LOGIN -> LIBRARIES
            MoreTarget.DOWNLOAD_LIST -> DOWNLOADS
            MoreTarget.TASKS -> TASKS
            else -> MENU
        }
    }
}

/** View models of the "更多" pages, all owned by the Activity. */
internal class MoreModels(
    val libraries: LibraryListViewModel,
    val oneDrive: OneDriveAuthorizationViewModel,
    val directories: OneDriveLibraryViewModel,
    val metadata: MetadataViewModel,
    val downloads: DownloadViewModel,
    val cleanup: CleanupViewModel,
    val tasks: TaskViewModel,
    val open: OpenViewModel,
)

/**
 * What the pages ask of the Activity: the system directory picker, the browser login (asking which
 * account to use when [login]'s argument is true) and the notification permission.
 */
internal class MoreActions(
    val selectDirectory: () -> Unit,
    val login: (chooseAccount: Boolean) -> Unit,
    val requestNotifications: () -> Unit,
)

/**
 * The "更多" tab (R30): a grouped, paged menu with sub-pages for metadata, storage, tasks, settings and
 * about. The top bar has a search icon instead of a resident search box; its search only filters the
 * menu names. [request] (from the library or a notification) opens a page once. Nothing here scrolls
 * or animates, and nothing contacts a source except the explicit sync, browse and login actions.
 */
@Composable
internal fun MoreScreen(
    models: MoreModels, actions: MoreActions, pickerOpen: Boolean, request: Int?, onRequestHandled: () -> Unit, showLibrary: () -> Unit,
) {
    var pageName by rememberSaveable { mutableStateOf(MorePage.MENU.name) }
    val page = MorePage.valueOf(pageName)
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuPage by rememberSaveable { mutableIntStateOf(0) }
    var licenseId by rememberSaveable { mutableStateOf("") }
    val show = { target: MorePage ->
        searching = false
        pageName = target.name
    }
    LaunchedEffect(request) {
        if (request != null) {
            val target = MorePage.of(request)
            show(target)
            if (target == MorePage.MENU) menuPage = 0
            onRequestHandled()
        }
    }
    val metadata = models.metadata
    DisposableEffect(metadata) {
        metadata.setVisible(true)
        models.tasks.loadSettings()
        onDispose { metadata.setVisible(false) }
    }
    LaunchedEffect(pickerOpen, models.oneDrive.status) {
        if (!pickerOpen) {
            models.libraries.refresh()
            models.directories.restore()
        }
    }
    LaunchedEffect(models.libraries.revision) {
        metadata.restore()
        models.downloads.restore()
    }
    LaunchedEffect(models.cleanup.revision) {
        metadata.restore()
        models.downloads.restore()
    }
    val toParent: () -> Unit = { page.parent?.let(show) }
    // In the wizard, back goes one step back; on the first step it cancels and returns to the list.
    val back: () -> Unit = if (page == MorePage.ADD_LIBRARY) ({ models.libraries.back(toParent) }) else toParent
    BackHandler(enabled = searching || page.parent != null) {
        if (searching) searching = false else back()
    }
    Column(Modifier.fillMaxSize()) {
        when {
            searching -> MoreSearchBar(query, { query = it }) { searching = false }
            page == MorePage.MENU -> MoreTopBar(stringResource(page.title), null) {
                IconAction(R.drawable.ic_search, stringResource(R.string.more_search), true, Modifier.testTag("more_search_button"),
                    iconSize = SEARCH_ICON_SIZE) {
                    query = ""
                    searching = true
                }
            }
            page == MorePage.LIBRARIES -> LibrariesTopBar(models.libraries, back) { show(MorePage.ADD_LIBRARY) }
            page == MorePage.ADD_LIBRARY -> AddLibraryTopBar(models.libraries, models.oneDrive, pickerOpen, back) {
                show(MorePage.LIBRARIES)
                showLibrary()
            }
            else -> MoreTopBar(stringResource(page.title), back)
        }
        HorizontalRule()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (searching) {
                MenuSearchResults(menuEntries(models, show, includeConditional = false), query)
            } else when (page) {
                MorePage.MENU -> PagedEntries(menuEntries(models, show), menuPage, { menuPage = it }, Modifier.testTag("more_menu"), "more")
                MorePage.LIBRARIES -> LibrariesPage(models.libraries, models.oneDrive, actions)
                MorePage.ADD_LIBRARY -> AddLibraryPage(models.libraries, models.oneDrive, models.directories, actions, pickerOpen)
                MorePage.DOWNLOADS -> DownloadsPage(models.downloads, models.open::open)
                MorePage.CLEAR_METADATA -> CleanupPage(models.cleanup, CleanupKind.METADATA)
                MorePage.CLEAR_OTHERS -> CleanupPage(models.cleanup, CleanupKind.OTHER_LIBRARIES)
                MorePage.TASKS -> TaskScreen(models.tasks)
                MorePage.BACKGROUND -> BackgroundSettings(models.tasks, actions.requestNotifications)
                MorePage.FORMATS -> FormatPriorityPage(metadata)
                MorePage.READ_COLUMN -> ReadColumnPage(metadata)
                MorePage.ABOUT -> AboutPage(show)
                MorePage.PROJECT_LICENSE -> LicenseTextPage(R.raw.license_gpl_3_0, "project_license")
                MorePage.NOTICES -> NoticesPage { licenseId = it; show(MorePage.NOTICE_LICENSE) }
                MorePage.NOTICE_LICENSE -> LICENSE_TEXTS[licenseId]?.let { LicenseTextPage(it, "notice_license") }
            }
        }
    }
}

/** Title with a back icon on sub-pages, then the page's [actions] at the end; the menu has the search icon there. */
@Composable
internal fun MoreTopBar(
    title: String, onBack: (() -> Unit)?, backDescription: String = stringResource(R.string.more_back),
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopBar(
        Modifier.testTag("more_top_bar"),
        navigation = onBack?.let { { BackAction(backDescription, Modifier.testTag("more_back"), it) } },
        actions = actions,
    ) {
        TopBarTitle(title, Modifier.testTag("more_title"))
    }
}

/** Back and the search input; the menu filters while typing, as it only searches menu names. */
@Composable
private fun MoreSearchBar(query: String, onQuery: (String) -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopBar(
        Modifier.testTag("more_search_bar"),
        navigation = { BackAction(stringResource(R.string.more_back), Modifier.testTag("more_search_back"), onBack) },
        actions = { Spacer(Modifier.width(PAGE_MARGIN)) },
    ) {
        SearchField(query, onQuery, stringResource(R.string.more_search_input), focus, "more_search_input",
            stringResource(R.string.more_search_clear), "more_search_clear", onClear = { onQuery("") })
    }
}

/** Choices whose name contains every word of [query], under their group headings; nothing else is searched. */
@Composable
internal fun MenuSearchResults(entries: List<MenuEntry>, query: String) {
    var page by remember(query) { mutableIntStateOf(0) }
    val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val results = buildList {
        var heading: MenuEntry.Heading? = null
        entries.forEach { entry ->
            when (entry) {
                is MenuEntry.Heading -> heading = entry
                is MenuEntry.Choice -> if (words.isNotEmpty() && words.all { it in entry.label.lowercase(Locale.ROOT) }) {
                    heading?.let { add(it); heading = null }
                    add(entry)
                }
                else -> Unit
            }
        }
    }
    if (words.isNotEmpty() && results.isEmpty()) {
        EmptyMessage(stringResource(R.string.more_search_empty), Modifier.testTag("more_search_empty"))
    } else {
        PagedEntries(results, page, { page = it }, Modifier.testTag("more_search_results"), "more_search")
    }
}

/**
 * The menu in five groups. The metadata group describes the library first: none chosen (with the
 * settings entry) or no complete import; a running or failed sync adds its state, and the sync action
 * shows when the last sync succeeded. [includeConditional] false leaves out that state and the extra settings entry, for search.
 */
@Composable
private fun menuEntries(models: MoreModels, show: (MorePage) -> Unit, includeConditional: Boolean = true): List<MenuEntry> {
    val metadata = models.metadata
    val selection = metadata.selection
    val configured = selection?.location != null
    val imported = metadata.imported
    val syncState = syncStatus(metadata.syncRecord, metadata.syncRejected)
    return buildList {
        add(MenuEntry.Heading(stringResource(R.string.more_group_metadata)))
        if (includeConditional) {
            when {
                metadata.readFailed -> stringResource(R.string.metadata_local_error)
                !configured -> stringResource(R.string.more_unconfigured)
                imported == null -> stringResource(R.string.more_no_metadata)
                else -> null
            }?.let { add(MenuEntry.Note("more_library_state", it)) }
            if (syncState != null) add(MenuEntry.Note("more_sync_status", syncState))
            if (!configured) add(MenuEntry.Choice("more_configure", stringResource(R.string.more_configure)) { show(MorePage.LIBRARIES) })
        }
        // The last success is one short line, so it sits under the sync action instead of taking a note.
        val syncedAt = imported?.takeIf { includeConditional && configured && !metadata.readFailed }?.let {
            stringResource(R.string.metadata_synced_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.importedAt)))
        }
        add(MenuEntry.Choice("more_sync", stringResource(R.string.task_sync_now), enabled = configured && !metadata.busy, supporting = syncedAt) {
            metadata.synchronize()
        })
        add(MenuEntry.Choice("more_clear_metadata", stringResource(R.string.more_clear_metadata), enabled = configured) {
            show(MorePage.CLEAR_METADATA)
        })
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.more_group_storage)))
        add(MenuEntry.Choice("more_downloads", stringResource(R.string.more_downloads)) { show(MorePage.DOWNLOADS) })
        add(MenuEntry.Choice("more_clear_others", stringResource(R.string.more_clear_others)) { show(MorePage.CLEAR_OTHERS) })
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.more_group_tasks)))
        add(MenuEntry.Choice("more_tasks", stringResource(R.string.more_tasks)) { show(MorePage.TASKS) })
        add(MenuEntry.Choice("more_background", stringResource(R.string.more_background)) { show(MorePage.BACKGROUND) })
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.more_group_settings)))
        add(MenuEntry.Choice("more_libraries", stringResource(R.string.more_libraries)) { show(MorePage.LIBRARIES) })
        // Search also finds adding a library, which lives one level down.
        if (!includeConditional) {
            add(MenuEntry.Choice("more_add_library", stringResource(R.string.libraries_add)) { show(MorePage.ADD_LIBRARY) })
        }
        val tasks = models.tasks
        add(MenuEntry.Choice("more_startup_sync", stringResource(R.string.more_startup_sync),
            selected = tasks.automaticSync, multiple = true,
            enabled = tasks.settingsLoaded) { tasks.toggleStartup() })
        add(MenuEntry.Choice("more_formats", stringResource(R.string.more_formats)) { show(MorePage.FORMATS) })
        add(MenuEntry.Choice("more_read_column", stringResource(R.string.more_read_column)) { show(MorePage.READ_COLUMN) })
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.more_group_about)))
        add(MenuEntry.Choice("more_about", stringResource(R.string.more_about)) { show(MorePage.ABOUT) })
    }
}

/** A sync worth mentioning beside the last success: one that is pending, waiting, paused or did not complete. */
@Composable
internal fun syncStatus(record: TaskRecord?, rejected: Boolean): String? {
    if (rejected) return stringResource(R.string.more_sync_rejected)
    return when (val state = record?.state) {
        null -> null
        TaskState.Queued -> stringResource(R.string.more_sync_queued)
        is TaskState.Running -> stringResource(R.string.more_sync_running)
        is TaskState.Waiting -> stringResource(R.string.more_sync_waiting,
            state.reasons.map { stringResource(waitingResource(it)) }.joinToString(stringResource(R.string.list_separator)))
        is TaskState.Paused -> stringResource(R.string.more_sync_paused)
        is TaskState.Finished -> when (val result = state.result) {
            TaskResult.Completed, is TaskResult.CompletedWithBookFailures -> null
            is TaskResult.Cancelled -> stringResource(R.string.more_sync_cancelled)
            is TaskResult.Failed -> stringResource(R.string.more_sync_failed, stringResource(taskErrorResource(result.failure.error)))
        }
    }
}
