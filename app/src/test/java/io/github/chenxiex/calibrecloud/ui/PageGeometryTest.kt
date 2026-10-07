package io.github.chenxiex.calibrecloud.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageGeometryTest {
    private fun assertGrid(columns: Int, rows: Int, cellWidth: Float, geometry: PageGeometry) {
        assertEquals(columns to rows, geometry.columns to geometry.rows)
        assertEquals(cellWidth, geometry.cellWidth, 0.01f)
        // The cover keeps 2:3 inside the 4 dp inset.
        assertEquals((cellWidth - 8f) * COVER_ASPECT + 8f, geometry.cellHeight, 0.01f)
    }

    @Test
    fun gridFitsTheMostMinimumCellsThenGrowsThemUntilOneDirectionIsFull() {
        // 96 dp minimum cells are 140 dp high: 3 columns and 2 rows; the width fills first.
        assertGrid(3, 2, 120f, gridGeometry(360f, 400f, 96f, 4f))
        // The PA6 content area: a third row fits, so the height fills first and columns gain gaps.
        val pa6 = gridGeometry(460.4f, 441.8f, 96f, 4f)
        assertGrid(4, 3, 100.84f, pa6)
        assertEquals(441.8f, pa6.cellHeight * pa6.rows, 0.01f)
        // Rotated: more columns, one row, the width fills.
        assertGrid(7, 1, 720f / 7, gridGeometry(720f, 200f, 96f, 4f))
    }

    @Test
    fun tinyAreasStillShowOneItem() {
        assertEquals(1, gridGeometry(50f, 10f, 96f, 4f).capacity)
        assertEquals(PageGeometry(1, 1, 360f, 10f), listGeometry(360f, 10f, 72f))
    }

    @Test
    fun listFitsTheMostMinimumRowsThenStretchesThem() {
        assertEquals(PageGeometry(1, 6, 360f, 75f), listGeometry(360f, 450f, 72f))
    }

    @Test
    fun resizeKeepsTheFirstVisibleItemOnTheShownPage() {
        // Item 20 was first on a 10-item page; with 8 per page it is on the page starting at 16.
        assertEquals(20, pageStart(20, 10))
        assertEquals(16, pageStart(20, 8))
        assertEquals(20, pageStart(23, 4))
        assertEquals(0, pageStart(-3, 4))
    }

    @Test
    fun pageCountsCoverEmptyAndPartialPages() {
        assertEquals(1, pageCount(0, 6))
        assertEquals(1, pageCount(6, 6))
        assertEquals(2, pageCount(7, 6))
        assertEquals(6, lastPageStart(7, 6))
        assertEquals(0, lastPageStart(0, 6))
    }

    @Test
    fun groupedRowsKeepHeadingsWithTheirChoicesAndDropSeparatorsAtPageEdges() {
        val choice = PageBlock(40f)
        val heading = PageBlock(40f, keepWithNext = true)
        val rule = PageBlock(10f, separator = true)
        val blocks = listOf(choice, choice, rule, heading, choice, choice, rule, heading, choice)
        // Everything fits: one page with every row.
        assertEquals(listOf((0..8).toList()), paginate(blocks, 1000f))
        // 140 fits two choices, the rule and the heading, but the heading moves on with its choices.
        assertEquals(listOf(listOf(0, 1), listOf(3, 4, 5), listOf(7, 8)), paginate(blocks, 140f))
        // A row taller than the page is still shown alone.
        assertEquals(listOf(listOf(0), listOf(1)), paginate(listOf(PageBlock(90f), PageBlock(90f)), 50f))
    }
}
