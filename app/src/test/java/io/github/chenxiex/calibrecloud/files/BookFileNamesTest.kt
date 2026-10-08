package io.github.chenxiex.calibrecloud.files

import io.github.chenxiex.calibrecloud.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFileNamesTest {
    private val epub = BookFormat.parse("EPUB")

    private fun name(title: String, format: BookFormat = epub, id: Long = 42) = BookFileNames.readerName(title, id, format, "未命名书籍-$id")

    @Test
    fun ordinaryTitleTextIsKept() {
        assertEquals("三体：地球往事-42.epub", name("三体：地球往事"))
        assertEquals("Re: Zero? \"Vol. 1\"-42.pdf", name("Re: Zero? \"Vol. 1\"", BookFormat.parse("pdf")))
    }

    @Test
    fun unsafeCharactersAreNormalized() {
        // Controls become spaces, bidi overrides disappear, separators cannot form a path.
        assertEquals("a b c-42.epub", name("a\nb\u0000\tc"))
        assertEquals("evilbupe.txt-42.epub", name("evil‮bupe.txt"))
        assertEquals("AC_DC_Live-42.epub", name("AC/DC\\Live"))
        assertEquals("hidden-42.epub", name(" ..hidden.. "))
    }

    @Test
    fun emptyTitleUsesTheUntitledName() {
        assertEquals("未命名书籍-42.epub", name(""))
        assertEquals("未命名书籍-42.epub", name(" \n​. "))
    }

    @Test
    fun longTitlesFitTheNameLimitWithoutSplittingCharacters() {
        val result = name("书".repeat(200) + "😀".repeat(10))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(result.endsWith("-42.epub"))
        assertTrue(result.removeSuffix("-42.epub").all { it == '书' })
    }

    @Test
    fun sameTitleBooksKeepTheirOwnBookId() {
        assertEquals("A Brief Orchard-17.epub", name("A Brief Orchard", id = 17))
        assertEquals("A Brief Orchard-161.epub", name("A Brief Orchard", id = 161))
        val long = name("书".repeat(200), id = 123456)
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(long.startsWith("书") && long.endsWith("-123456.epub"))
    }

    @Test
    fun mimeTypesComeFromTheFormatThenTheSystem() {
        assertEquals("application/epub+zip", BookMimeTypes.of(epub) { null })
        assertEquals("application/pdf", BookMimeTypes.of(BookFormat.parse("PDF")) { null })
        assertEquals("application/x-special", BookMimeTypes.of(BookFormat.parse("SPECIAL")) { if (it == "special") "application/x-special" else null })
        assertEquals(BookMimeTypes.FALLBACK, BookMimeTypes.of(BookFormat.parse("UNKNOWN1")) { null })
    }
}
