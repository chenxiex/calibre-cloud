package io.github.chenxiex.calibrecloud.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenxiex.calibrecloud.R

private const val TAB_LIBRARY = 0
private const val TAB_MORE = 1

/**
 * Top-level frame: the selected tab fills the space above a static bottom bar. Test tags are exposed
 * as resource IDs so device checks can select controls without relying on position or page number.
 * [more] draws the "更多" page for the given temporary page index.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MainScreen(library: LibraryViewModel, more: @Composable (page: Int, onPage: (Int) -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_LIBRARY) }
    var morePage by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics { testTagsAsResourceId = true },
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == TAB_LIBRARY) {
                LibraryScreen(library) { target ->
                    morePage = target
                    tab = TAB_MORE
                }
            } else {
                more(morePage) { morePage = it }
            }
        }
        HorizontalRule()
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).testTag("bottom_bar")) {
        BottomTab(stringResource(R.string.nav_library), R.drawable.ic_library_outline, R.drawable.ic_library_filled,
            selected == TAB_LIBRARY, "nav_library", Modifier.weight(1f)) { onSelect(TAB_LIBRARY) }
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
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { this.selected = selected; role = Role.Tab },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(if (selected) filled else outline), null, Modifier.size(26.dp), tint = Color.Black)
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
