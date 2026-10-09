package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R
import kotlin.math.abs

/**
 * The one paged area of the app: [content] shows page [page] of [pages], a swipe turns the page and the
 * [PageBar] sits below when [showBar]. Every paged list, grid, menu or popup uses this, so paging looks
 * and responds the same everywhere. The area is tagged `<prefix>_pager`.
 */
@Composable
internal fun PagedArea(
    page: Int,
    pages: Int,
    tagPrefix: String,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showBar: Boolean = pages > 1,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().pageSwipe(page, pages, onPage).testTag("${tagPrefix}_pager")) { content() }
        if (showBar) PageBar(page, pages, tagPrefix, onPage)
    }
}

/** Shortest swipe that turns a page; shorter drags are ignored but still do not count as taps. */
private val SWIPE_DISTANCE = 32.dp

/**
 * Left-to-right or top-to-bottom shows the previous page, the reverse the next one; the larger
 * displacement decides. Nothing follows the finger: the page changes once, when the finger lifts. A
 * drag past the touch slop is consumed so the item under it is not also tapped.
 */
private fun Modifier.pageSwipe(page: Int, pages: Int, onPage: (Int) -> Unit): Modifier = pointerInput(page, pages, onPage) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = Offset.Zero
        var dragging = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            moved = change.position - down.position
            if (!dragging && moved.getDistance() > viewConfiguration.touchSlop) dragging = true
            if (dragging) change.consume()
            if (!change.pressed) break
        }
        if (!dragging || maxOf(abs(moved.x), abs(moved.y)) < SWIPE_DISTANCE.toPx()) return@awaitEachGesture
        val backward = if (abs(moved.x) >= abs(moved.y)) moved.x > 0 else moved.y > 0
        val target = if (backward) page - 1 else page + 1
        if (target in 0 until pages) onPage(target)
    }
}

/**
 * KOReader-style page row: first, previous, "current / total", next, last. The ends are disabled on the
 * first and last page. Tags are `<prefix>_first_page`, `_previous_page`, `_page_status`, `_next_page`
 * and `_last_page`.
 */
@Composable
internal fun PageBar(page: Int, pages: Int, tagPrefix: String, onPage: (Int) -> Unit) {
    val atStart = page <= 0
    val atEnd = page + 1 >= pages
    Row(Modifier.fillMaxWidth().height(PAGE_BAR_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        IconAction(R.drawable.ic_first_page, stringResource(R.string.page_first), !atStart,
            Modifier.testTag("${tagPrefix}_first_page")) { onPage(0) }
        IconAction(R.drawable.ic_previous_page, stringResource(R.string.page_previous), !atStart,
            Modifier.testTag("${tagPrefix}_previous_page")) { onPage(page - 1) }
        val description = stringResource(R.string.page_status_description, page + 1, pages)
        Text(
            stringResource(R.string.page_status, page + 1, pages),
            Modifier.weight(1f).testTag("${tagPrefix}_page_status").semantics { contentDescription = description },
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
        )
        IconAction(R.drawable.ic_next_page, stringResource(R.string.page_next), !atEnd,
            Modifier.testTag("${tagPrefix}_next_page")) { onPage(page + 1) }
        IconAction(R.drawable.ic_last_page, stringResource(R.string.page_last), !atEnd,
            Modifier.testTag("${tagPrefix}_last_page")) { onPage(pages - 1) }
    }
}
