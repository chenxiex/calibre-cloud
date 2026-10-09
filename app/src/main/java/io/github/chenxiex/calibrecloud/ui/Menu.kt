package io.github.chenxiex.calibrecloud.ui

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.github.chenxiex.calibrecloud.R

/** A row of a grouped menu or settings list; each kind has a fixed height, so pages are laid out by height. */
internal sealed interface MenuEntry {
    val height: Dp

    data object Rule : MenuEntry {
        override val height get() = RULE_HEIGHT
    }

    class Heading(val label: String) : MenuEntry {
        override val height get() = HEADING_HEIGHT
    }

    /** Explains why the choices that follow are unavailable. */
    class Note(val tag: String, val text: String) : MenuEntry {
        override val height get() = NOTE_HEIGHT
    }

    /** A place in an order, moved by the up and down buttons at the row end (tagged `<tag>_up` and `<tag>_down`). */
    class Ordered(
        val tag: String, val label: String, val upDescription: String, val downDescription: String,
        val canUp: Boolean, val canDown: Boolean, val onUp: () -> Unit, val onDown: () -> Unit,
    ) : MenuEntry {
        override val height get() = ROW_HEIGHT
    }

    /**
     * An action, or an option when [selected] is set: a leading radio shows it (a check box when
     * [multiple] options may be chosen) and a chosen option is bold (Q65). [trailing] is drawn at the row
     * end of a chosen option, such as the sort direction, and [trailingDescription] states it.
     */
    class Choice(
        val tag: String, val label: String, @DrawableRes val icon: Int? = null, val selected: Boolean? = null,
        val multiple: Boolean = false, @DrawableRes val trailing: Int? = null, val trailingDescription: String? = null,
        val enabled: Boolean = true, val action: () -> Unit,
    ) : MenuEntry {
        override val height get() = ROW_HEIGHT
    }
}

/**
 * Lays out grouped rows by height; the page row only appears when the entries need more than one page.
 * When [focus] changes, the page that holds that entry index is shown.
 */
@Composable
internal fun PagedEntries(entries: List<MenuEntry>, page: Int, onPage: (Int) -> Unit, modifier: Modifier, tagPrefix: String, focus: Int? = null) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val blocks = entries.map { PageBlock(it.height.value, keepWithNext = it is MenuEntry.Heading, separator = it is MenuEntry.Rule) }
        val pages = paginate(blocks, maxHeight.value).takeIf { it.size <= 1 }
            ?: paginate(blocks, maxHeight.value - PAGE_BAR_HEIGHT.value)
        val current = page.coerceIn(0, pages.size - 1)
        LaunchedEffect(current, page) { if (current != page) onPage(current) }
        LaunchedEffect(focus) {
            val target = focus?.let { index -> pages.indexOfFirst { index in it } } ?: -1
            if (target >= 0 && target != current) onPage(target)
        }
        PagedArea(current, pages.size, tagPrefix, onPage, Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                pages[current].forEach { MenuRow(entries[it]) }
            }
        }
    }
}

@Composable
internal fun MenuRow(entry: MenuEntry) {
    when (entry) {
        MenuEntry.Rule -> Box(Modifier.fillMaxWidth().height(entry.height), contentAlignment = Alignment.Center) { InsetRule() }
        is MenuEntry.Note -> Box(Modifier.fillMaxWidth().height(entry.height).padding(horizontal = PAGE_MARGIN, vertical = TIGHT_GAP).testTag(entry.tag),
            contentAlignment = Alignment.CenterStart) {
            Text(entry.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        is MenuEntry.Heading -> Box(Modifier.fillMaxWidth().height(entry.height).padding(horizontal = PAGE_MARGIN),
            contentAlignment = Alignment.BottomStart) {
            Heading(entry.label, Modifier.padding(bottom = TIGHT_GAP))
        }
        is MenuEntry.Ordered -> ListItem(entry.label, Modifier.testTag(entry.tag), entry.height) {
            IconAction(R.drawable.ic_arrow_up, entry.upDescription, entry.canUp, Modifier.testTag("${entry.tag}_up"), onClick = entry.onUp)
            IconAction(R.drawable.ic_arrow_down, entry.downDescription, entry.canDown, Modifier.testTag("${entry.tag}_down"), onClick = entry.onDown)
        }
        is MenuEntry.Choice -> {
            val chosen = entry.selected == true
            val state = entry.trailingDescription?.takeIf { entry.trailing != null } ?: stringResource(R.string.library_menu_chosen)
            ListItem(
                entry.label,
                Modifier.testTag(entry.tag).semantics {
                    selected = chosen
                    if (chosen || entry.trailing != null) stateDescription = state
                },
                entry.height, entry.enabled, bold = chosen, onClick = entry.action,
                role = when {
                    entry.selected == null -> Role.Button
                    entry.multiple -> Role.Checkbox
                    else -> Role.RadioButton
                },
                leading = if (entry.selected == null && entry.icon == null) null else ({ ChoiceLeading(entry) }),
                trailing = entry.trailing?.let { icon -> { IconSlot(icon, null, enabled = entry.enabled) } },
            )
        }
    }
}

/** The option's radio or check box, then its icon. */
@Composable
private fun ChoiceLeading(entry: MenuEntry.Choice) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (entry.selected != null) SelectionMark(entry.selected, entry.multiple, enabled = entry.enabled)
        if (entry.selected != null && entry.icon != null) Spacer(Modifier.width(LEADING_GAP))
        if (entry.icon != null) {
            Icon(painterResource(entry.icon), null, Modifier.size(ICON_SIZE), tint = if (entry.enabled) INK else DISABLED_TINT)
        }
    }
}
