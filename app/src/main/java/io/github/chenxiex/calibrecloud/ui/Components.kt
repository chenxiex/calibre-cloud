package io.github.chenxiex.calibrecloud.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R

/*
 * Controls shared by every page (R20, Q64–Q68). A control used for the same purpose on two pages comes
 * from here, so it looks and responds the same; pages add layout and content, not their own styling.
 * Paging lives beside them in PageControls.kt.
 */

/**
 * Borderless icon button with a static press and a [ICON_TOUCH_SIZE] touch area. A disabled icon is
 * grey; an [active] one (such as the view button while its menu is open) is white on a black tile, so
 * neither state relies on hue.
 */
@Composable
internal fun IconAction(
    @DrawableRes icon: Int,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = ICON_TOUCH_SIZE,
    iconSize: Dp = ICON_SIZE,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(size).tap(enabled, onClick = onClick).semantics {
            contentDescription = description
            if (active) selected = true
        },
        contentAlignment = Alignment.Center,
    ) {
        val tile = if (active) Modifier.background(INK, SMALL_SHAPE) else Modifier
        Box(tile.size(size - 8.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(iconSize), tint = when {
                active -> PAPER
                enabled -> INK
                else -> DISABLED_TINT
            })
        }
    }
}

/** A plain icon in a [ICON_TOUCH_SIZE] slot, so it lines up with the [IconAction]s beside it. */
@Composable
internal fun IconSlot(@DrawableRes icon: Int, description: String?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(Modifier.size(ICON_TOUCH_SIZE), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, modifier.size(ICON_SIZE), tint = if (enabled) INK else DISABLED_TINT)
    }
}

/** The one divider: between the bars and the content, between menu groups and after record rows. */
@Composable
internal fun HorizontalRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(BORDER).background(INK))
}

/** A divider that starts and ends at the page margins, after a row of a record list. */
@Composable
internal fun InsetRule() = HorizontalRule(Modifier.padding(horizontal = PAGE_MARGIN))

/**
 * A top bar: [navigation] (usually [BackAction]) or the page margin, then [content] (usually
 * [TopBarTitle]) and the [actions] at the end.
 */
@Composable
internal fun TopBar(
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().height(BAR_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        if (navigation != null) navigation() else Spacer(Modifier.width(PAGE_MARGIN))
        content()
        actions()
    }
}

@Composable
internal fun RowScope.TopBarTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun BackAction(description: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    IconAction(R.drawable.ic_back, description, true, modifier, onClick = onClick)

/** Text button weight (Q67): one [PRIMARY] per page at most, [TEXT] for cancel and back. */
internal enum class ButtonKind {
    /** Black with white text: the page's main action, including a destructive one named for its effect. */
    PRIMARY,
    /** A thin black frame: other actions. */
    SECONDARY,
    /** Text only: cancel, back and done. */
    TEXT,
}

/**
 * A text button drawn [CONTROL_HEIGHT] tall inside a [ICON_TOUCH_SIZE] touch area. Disabled buttons lose
 * their fill and turn grey, so the state shows without hue. [description] replaces the label for
 * accessibility when the label alone is too short.
 */
@Composable
internal fun ActionButton(
    label: String, enabled: Boolean, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.SECONDARY,
    description: String? = null, onClick: () -> Unit,
) {
    val tint = if (enabled) INK else DISABLED_TINT
    val frame = when {
        kind == ButtonKind.TEXT -> Modifier
        kind == ButtonKind.PRIMARY && enabled -> Modifier.background(INK, SMALL_SHAPE)
        else -> Modifier.border(BORDER, tint, SMALL_SHAPE)
    }
    Box(
        modifier.heightIn(min = ICON_TOUCH_SIZE).widthIn(min = ICON_TOUCH_SIZE).tap(enabled, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(frame.height(CONTROL_HEIGHT).padding(horizontal = if (kind == ButtonKind.TEXT) PAGE_MARGIN else 16.dp),
            contentAlignment = Alignment.Center) {
            Text(label, color = if (kind == ButtonKind.PRIMARY && enabled) PAPER else tint, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A chip for a field, tab or segment (Q65): a framed pill, white on black and bold when [selected].
 * It is drawn [CHIP_HEIGHT] tall inside a [ICON_TOUCH_SIZE] touch area.
 */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, role: Role = Role.Button, onClick: () -> Unit) {
    val chosen = stringResource(R.string.library_menu_chosen)
    Box(
        modifier.heightIn(min = ICON_TOUCH_SIZE).tap(role = role, onClick = onClick).semantics {
            this.selected = selected
            if (selected) stateDescription = chosen
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(CHIP_HEIGHT).clip(PILL_SHAPE).background(if (selected) INK else PAPER).border(BORDER, INK, PILL_SHAPE)
                .padding(horizontal = SECTION_GAP),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (selected) PAPER else INK, style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The leading mark of an option row (Q65): a radio for one of several, a check box when [multiple]
 * may be chosen. Chosen marks are filled black, so they read without colour.
 */
@Composable
internal fun SelectionMark(
    selected: Boolean, multiple: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onImage: Boolean = false,
) {
    val tint = if (enabled) INK else DISABLED_TINT
    val shape = if (multiple) SMALL_SHAPE else MARK_SHAPE
    Box(
        // On a cover a thin white rim keeps the mark apart from dark pixels.
        modifier.then(if (onImage) Modifier.background(PAPER, shape).padding(BORDER) else Modifier).size(SELECTION_MARK_SIZE).background(if (selected && multiple) tint else PAPER, shape).border(FRAME, tint, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            selected && multiple -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = PAPER)
            selected -> Box(Modifier.size(10.dp).background(tint, MARK_SHAPE))
        }
    }
}

internal val SELECTION_MARK_SIZE = 20.dp

/** A small framed label on a cover or in a row, such as "已读" or a missing source. */
@Composable
internal fun TagLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.background(PAPER, PILL_SHAPE).border(BORDER, INK, PILL_SHAPE).padding(horizontal = 6.dp),
        style = MaterialTheme.typography.labelSmall, maxLines = 1,
    )
}

/**
 * A list row: [leading] (a mark or icon), the [headline] over an optional [supporting] line, then
 * [trailing] controls in [ICON_TOUCH_SIZE] slots. [bold] marks a chosen option or a record's title. A tappable row ([onClick]) is one touch target; a
 * [divider] row keeps the rule inside [height], so a page of rows fits the measured space.
 */
@Composable
internal fun ListItem(
    headline: String,
    modifier: Modifier = Modifier,
    height: Dp = ROW_HEIGHT,
    enabled: Boolean = true,
    bold: Boolean = false,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
    role: Role? = Role.Button,
    leading: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().height(height).then(if (onClick != null) Modifier.tap(enabled, role, onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(start = PAGE_MARGIN, end = if (trailing == null) PAGE_MARGIN else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(LEADING_GAP))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(headline, color = if (enabled) INK else DISABLED_TINT,
                    style = if (supporting != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                supporting?.invoke()
            }
            trailing?.invoke(this)
        }
        if (divider) InsetRule()
    }
}

/** The supporting line of a [ListItem]. */
@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A group heading of a page or panel. */
@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A short page of text and buttons from the top, at the page margins, never scrolled. */
@Composable
internal fun FormColumn(tag: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(PAGE_MARGIN).testTag(tag), verticalArrangement = Arrangement.spacedBy(SECTION_GAP), content = content)
}

/** Why a page or list shows nothing, centred, with the [actions] that resolve it below; [modifier] (such as a tag) applies to the text. */
@Composable
internal fun EmptyMessage(text: String, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(PAGE_MARGIN * 2), verticalArrangement = Arrangement.spacedBy(SECTION_GAP, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, modifier, textAlign = TextAlign.Center)
        actions()
    }
}

/**
 * A panel over the page, without animation or scrim: a tap outside or [onDismiss] closes it, taps on the
 * panel stay there. The dismissing layer is a sibling, not a parent, so it does not merge the panel's
 * semantics; [content] is framed with [FRAME] and [SMALL_SHAPE], [contentPadding] inside the frame.
 */
@Composable
internal fun OverlayPanel(
    onDismiss: () -> Unit, modifier: Modifier, alignment: Alignment = Alignment.Center, contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().tap(role = null, onClick = onDismiss))
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Column(
            modifier.background(PAPER, SMALL_SHAPE).border(FRAME, INK, SMALL_SHAPE)
                // Taps between rows must not reach the dismissing layer; a clickable here would merge the rows.
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A confirmation over the page: an optional [title], the exact range in [content], then cancel and the
 * primary [confirmLabel]. Nothing happens until confirmed; buttons are tagged `<tag>_cancel` and `<tag>_confirm`.
 */
@Composable
internal fun ConfirmPanel(
    tag: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit, title: String? = null,
    cancelTag: String = "${tag}_cancel", confirmTag: String = "${tag}_confirm",
    confirmLabel: String = stringResource(R.string.removal_confirm), content: @Composable ColumnScope.() -> Unit,
) {
    OverlayPanel(onCancel, Modifier.fillMaxWidth(0.86f).testTag(tag), contentPadding = PAGE_MARGIN) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.padding(vertical = SECTION_GAP), verticalArrangement = Arrangement.spacedBy(TIGHT_GAP)) { content() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SECTION_GAP, Alignment.End)) {
            ActionButton(stringResource(R.string.removal_cancel), enabled, Modifier.testTag(cancelTag), ButtonKind.TEXT, onClick = onCancel)
            ActionButton(confirmLabel, enabled, Modifier.testTag(confirmTag), ButtonKind.PRIMARY, onClick = onConfirm)
        }
    }
}

/**
 * The search input of a top bar: the glass, an optional [prefix] (the chosen field), the text tagged
 * [inputTag] and focused by [focus] and, while there is text, a clear icon tagged [clearTag].
 */
@Composable
internal fun RowScope.SearchField(
    value: String, onValue: (String) -> Unit, placeholder: String, focus: FocusRequester, inputTag: String,
    clearDescription: String, clearTag: String, onClear: () -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    prefix: @Composable () -> Unit = {},
) {
    Row(
        Modifier.weight(1f).height(CONTROL_HEIGHT).border(BORDER, INK, SMALL_SHAPE).padding(start = PAGE_MARGIN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(SMALL_ICON_SIZE), tint = INK)
        Spacer(Modifier.width(SECTION_GAP))
        prefix()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = DISABLED_TINT, maxLines = 1)
            BasicTextField(value, onValue, Modifier.fillMaxWidth().focusRequester(focus).testTag(inputTag).semantics { contentDescription = placeholder },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = INK), singleLine = true,
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
        }
        if (value.isNotEmpty()) {
            // The touch area keeps its full size beyond the drawn input.
            IconAction(R.drawable.ic_close, clearDescription, true, Modifier.testTag(clearTag).requiredSize(ICON_TOUCH_SIZE),
                iconSize = SMALL_ICON_SIZE, onClick = onClear)
        } else {
            Spacer(Modifier.width(SECTION_GAP))
        }
    }
}

/** Content centred in the remaining space, such as a still loading icon. */
@Composable
internal fun Centered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
}
