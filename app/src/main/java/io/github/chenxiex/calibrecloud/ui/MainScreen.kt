package io.github.chenxiex.calibrecloud.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.state.LastOpened

private const val TAB_LIBRARY = 0
private const val TAB_MORE = 1

/**
 * Top-level frame: the selected tab fills the space above a static bottom bar. Test tags are exposed
 * as resource IDs so device checks can select controls without relying on position or page number.
 * [more] draws the "更多" tab and opens the [MoreTarget] it is handed once. A book being opened shows its
 * mark: a cancellable progress mark while it downloads, a warning when it needs the user, whose
 * reason [notify] posts as a system notification. [moreRequest] (from such a notification) opens a
 * [MoreTarget] once. [notifyBatch] posts a batch action of the library that was not fully done. [startReader] hands a ready copy to the system, and leaving the page revokes the
 * wait so a finished download never opens a reader later.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MainScreen(
    library: LibraryViewModel,
    open: OpenViewModel,
    startReader: (OpenLaunch) -> LaunchOutcome,
    notify: (OpenNotice) -> Unit = {},
    notifyBatch: (BatchNotice) -> Unit = {},
    moreRequest: Int? = null,
    onMoreRequestHandled: () -> Unit = {},
    /** Changes whenever the current library may have changed on the more page, which keeps the tab. */
    libraryRevision: Int = 0,
    /** The "更多" tab; its last argument shows the library tab, as finishing the add-library wizard does. */
    more: @Composable (request: Int?, onRequestHandled: () -> Unit, showLibrary: () -> Unit) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_LIBRARY) }
    var moreTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val select = { target: Int ->
        if (target != tab) open.revoke()
        tab = target
        open.refresh()
    }
    val openMore = { target: Int ->
        moreTarget = target
        select(TAB_MORE)
    }
    LaunchedEffect(open, libraryRevision) { open.refresh() }
    val launch = open.launch
    LaunchedEffect(launch) { if (launch != null) open.launched(launch, startReader(launch)) }
    val notice = open.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            notify(notice)
            open.noticeHandled(notice.id)
        }
    }
    val batchNotice = library.notice
    LaunchedEffect(batchNotice) {
        if (batchNotice != null) {
            notifyBatch(batchNotice)
            library.noticeHandled(batchNotice)
        }
    }
    LaunchedEffect(moreRequest) {
        if (moreRequest != null) {
            openMore(moreRequest)
            onMoreRequestHandled()
        }
    }
    Column(
        Modifier.fillMaxSize().background(PAPER).windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics { testTagsAsResourceId = true },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == TAB_LIBRARY) {
                LibraryScreen(library, onOpen = open::open, onNoFormat = open::failNoFormat, mark = open.mark,
                    onCancelDownload = open::cancelDownload, downloads = open.downloads, onCancelTask = open::cancelTask, openMore = openMore)
            } else {
                more(moreTarget, { moreTarget = null }) {
                    select(TAB_LIBRARY)
                    library.refresh()
                }
            }
        }
        HorizontalRule()
        BottomBar(tab, open.lastOpened, open::openLastOpened, select)
    }
}

@Composable
private fun BottomBar(selected: Int, lastOpened: LastOpened?, onLastOpened: () -> Unit, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(BOTTOM_BAR_HEIGHT).testTag("bottom_bar")) {
        BottomTab(stringResource(R.string.nav_library), R.drawable.ic_library_outline, R.drawable.ic_library_filled,
            selected == TAB_LIBRARY, "nav_library", Modifier.weight(1f)) { onSelect(TAB_LIBRARY) }
        // Without a record the two tabs share the bar.
        if (lastOpened != null) LastOpenedTab(lastOpened, Modifier.weight(1f), onLastOpened)
        BottomTab(stringResource(R.string.nav_more), R.drawable.ic_more_outline, R.drawable.ic_more_filled,
            selected == TAB_MORE, "nav_more", Modifier.weight(1f)) { onSelect(TAB_MORE) }
    }
}

/** Borderless icon over a short label; the shown tab has a filled icon and bold label, so the state survives greyscale. */
@Composable
private fun BottomTab(
    label: String, @DrawableRes outline: Int, @DrawableRes filled: Int, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxHeight().testTag(tag)
            .tap(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(if (selected) filled else outline), null, Modifier.size(ICON_SIZE), tint = INK)
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
