package io.github.chenxiex.calibrecloud.library

import io.github.chenxiex.calibrecloud.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatOrderTest {
    private val epub = BookFormat.parse("EPUB")
    private val pdf = BookFormat.parse("PDF")
    private val azw3 = BookFormat.parse("AZW3")
    private val mobi = BookFormat.parse("MOBI")

    @Test
    fun storedOrderComesFirstAndNewFormatsFollowByName() {
        assertEquals(listOf(epub, azw3, mobi, pdf), formatOrder(listOf(epub), listOf(pdf, mobi, azw3, epub)))
        assertEquals(listOf(pdf, epub, azw3), formatOrder(listOf(pdf, epub), listOf(azw3, azw3)))
        // A saved format missing from the current import keeps its place for libraries that have it.
        assertEquals(listOf(mobi, epub), formatOrder(listOf(mobi), listOf(epub)))
    }

    @Test
    fun movingSwapsWithTheNeighbourAndStopsAtEitherEnd() {
        val order = listOf(epub, azw3, pdf)
        assertEquals(listOf(azw3, epub, pdf), order.moved(azw3, up = true))
        assertEquals(listOf(epub, pdf, azw3), order.moved(azw3, up = false))
        assertEquals(order, order.moved(epub, up = true))
        assertEquals(order, order.moved(pdf, up = false))
        assertEquals(order, order.moved(mobi, up = true))
    }
}
