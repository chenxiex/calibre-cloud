package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The one visual system of the app (R20, Q64–Q68): compact Material in black, white and one grey, with
 * no motion. Pages take colours, sizes, shapes and text styles from here and from the shared components
 * instead of writing their own values; a size only one page needs is a named constant on that page.
 */

/** Text, icons, borders and filled (chosen or primary) surfaces. */
internal val INK = Color.Black
/** Backgrounds, and text on [INK]. */
internal val PAPER = Color.White
/** Disabled text, icons and borders; lighter in greyscale, never the only sign of a state on its own. */
internal val DISABLED_TINT = Color(0xFF8A8A8A)

/** Smallest touch area of anything tappable (Q66); the drawn control may be smaller. */
internal val ICON_TOUCH_SIZE = 48.dp
internal val ICON_SIZE = 24.dp
/** The top bars' search icon is slightly smaller than the icons beside it (R21); its touch area is not. */
internal val SEARCH_ICON_SIZE = 20.dp
/** Icons inside a text line or an input, such as the search glass. */
internal val SMALL_ICON_SIZE = 18.dp

/** Left and right margin of page content; a 48dp icon at a bar's edge draws its glyph on the same line. */
internal val PAGE_MARGIN = 12.dp
/** Between sections, paragraphs, buttons and chips. */
internal val SECTION_GAP = 8.dp
/** Between a leading icon or mark and the text after it. */
internal val LEADING_GAP = 12.dp
/** Small separation inside one element, such as between a title and its supporting line. */
internal val TIGHT_GAP = 4.dp

/** Top bars and the page row. */
internal val BAR_HEIGHT = 48.dp
internal val PAGE_BAR_HEIGHT = 48.dp
internal val BOTTOM_BAR_HEIGHT = 52.dp
/** One-line list and menu rows. */
internal val ROW_HEIGHT = 48.dp
/** Rows with a headline and a supporting line. */
internal val TWO_LINE_ROW_HEIGHT = 56.dp
/** Group headings of menus, set on the row's bottom so they sit with the rows that follow. */
internal val HEADING_HEIGHT = 36.dp
/** Explanations inside menus: up to three supporting lines inside a [TIGHT_GAP] padding, at a slightly enlarged font scale. */
internal val NOTE_HEIGHT = 64.dp
/** A group divider of menus: the line and the space around it. */
internal val RULE_HEIGHT = 9.dp

/** Drawn height of buttons and inputs, inside the [ICON_TOUCH_SIZE] touch area. */
internal val CONTROL_HEIGHT = 40.dp
/** Drawn height of chips. */
internal val CHIP_HEIGHT = 32.dp

/** Lines of buttons, inputs, chips, tags, covers and dividers. */
internal val BORDER = 1.dp
/** Frames that stand apart from the page: option blocks, overlay panels and chosen items. */
internal val FRAME = 2.dp

/** Buttons, inputs, option blocks, cards, menus and panels (Q68). */
internal val SMALL_SHAPE = RoundedCornerShape(4.dp)
/** Chips and tags. */
internal val PILL_SHAPE = RoundedCornerShape(50)
/** Status marks stay round. */
internal val MARK_SHAPE = CircleShape

private val TYPOGRAPHY = Typography(
    // Top bar and panel titles.
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    // Group headings; slightly larger than the options under them (R21).
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    // Headlines of two-line rows.
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    // Default text and one-line rows.
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    // Paragraphs and notes.
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    // Supporting lines.
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    // Button labels.
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    // Chip labels.
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    // Tags, bottom tab labels and text on marks.
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 14.sp),
)

private val COLORS = lightColorScheme(
    primary = INK, onPrimary = PAPER, primaryContainer = INK, onPrimaryContainer = PAPER,
    secondary = INK, onSecondary = PAPER, secondaryContainer = PAPER, onSecondaryContainer = INK,
    tertiary = INK, onTertiary = PAPER,
    background = PAPER, onBackground = INK, surface = PAPER, onSurface = INK,
    surfaceVariant = PAPER, onSurfaceVariant = INK, outline = INK, outlineVariant = DISABLED_TINT,
)

/** Draws nothing on press, so no tap anywhere flashes or animates on e-ink. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}

/** The app's Material theme: monochrome colours, compact type, small corners and no press indication. */
@Composable
internal fun CalibreCloudTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = COLORS, typography = TYPOGRAPHY, shapes = Shapes(extraSmall = SMALL_SHAPE, small = SMALL_SHAPE)) {
        CompositionLocalProvider(LocalIndication provides NoIndication, content = content)
    }
}

/** A tap without ripple or press feedback (R20); every tappable element uses this. */
internal fun Modifier.tap(enabled: Boolean = true, role: Role? = Role.Button, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null, enabled = enabled, role = role, onClick = onClick)
