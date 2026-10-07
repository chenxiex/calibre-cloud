package io.github.chenxiex.calibrecloud.ui

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Cells that fit one page, always at least one so a tiny area still shows content. [cellWidth] and
 * [cellHeight] are the stretched cell size in dp; the slack left beside the cells is spread as gaps.
 */
internal data class PageGeometry(val columns: Int, val rows: Int, val cellWidth: Float, val cellHeight: Float) {
    val capacity: Int get() = columns * rows
}

/** Cover boxes are 2:3 like the stored covers. */
internal const val COVER_ASPECT = 1.5f

/**
 * Count first: as many columns and rows as fit cells of [minCellWidthDp], then the cells grow at the
 * cover's aspect until one direction is full. [insetDp] is the margin around each cover inside its cell.
 */
internal fun gridGeometry(widthDp: Float, heightDp: Float, minCellWidthDp: Float, insetDp: Float): PageGeometry {
    val heightOf = { width: Float -> (width - 2 * insetDp) * COVER_ASPECT + 2 * insetDp }
    val widthOf = { height: Float -> (height - 2 * insetDp) / COVER_ASPECT + 2 * insetDp }
    val columns = maxOf(1, floor(widthDp / minCellWidthDp).toInt())
    val rows = maxOf(1, floor(heightDp / heightOf(minCellWidthDp)).toInt())
    val cellWidth = minOf(widthDp / columns, widthOf(heightDp / rows)).coerceAtLeast(0f)
    return PageGeometry(columns, rows, cellWidth, heightOf(cellWidth).coerceAtLeast(0f))
}

/** As many rows of at least [minRowHeightDp] as fit, stretched to fill [heightDp]. */
internal fun listGeometry(widthDp: Float, heightDp: Float, minRowHeightDp: Float): PageGeometry {
    val rows = maxOf(1, floor(heightDp / minRowHeightDp).toInt())
    return PageGeometry(1, rows, widthDp, heightDp / rows)
}

/** First index of the page that contains [firstVisible]; keeps the first visible item on screen after a resize. */
internal fun pageStart(firstVisible: Int, capacity: Int): Int =
    if (capacity <= 0) 0 else firstVisible.coerceAtLeast(0) / capacity * capacity

internal fun pageCount(total: Int, capacity: Int): Int =
    if (capacity <= 0 || total <= 0) 1 else ceil(total / capacity.toDouble()).toInt()

/** Start of the last page, used when a refresh leaves the old offset beyond the end. */
internal fun lastPageStart(total: Int, capacity: Int): Int = (pageCount(total, capacity) - 1) * capacity.coerceAtLeast(1)

/** One row of a page whose rows differ in height, such as a grouped menu. */
internal data class PageBlock(
    val height: Float,
    /** A heading is not left alone at the bottom of a page. */
    val keepWithNext: Boolean = false,
    /** A separator is not drawn at the top or bottom of a page. */
    val separator: Boolean = false,
)

/** Splits [blocks] into pages that fit [available]; returns the shown block indices of each page. */
internal fun paginate(blocks: List<PageBlock>, available: Float): List<List<Int>> {
    val pages = mutableListOf<List<Int>>()
    var index = 0
    while (index < blocks.size) {
        if (blocks[index].separator && pages.isNotEmpty()) {
            index++
            continue
        }
        var end = index
        var used = 0f
        while (end < blocks.size && (end == index || used + blocks[end].height <= available)) {
            used += blocks[end].height
            end++
        }
        if (end < blocks.size && end - 1 > index && blocks[end - 1].keepWithNext) end--
        pages += (index until end).filterNot { blocks[it].separator && (it == index || it == end - 1) }
        index = end
    }
    return pages.ifEmpty { listOf(emptyList()) }
}
