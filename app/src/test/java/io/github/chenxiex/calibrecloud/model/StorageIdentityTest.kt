package io.github.chenxiex.calibrecloud.model

import org.junit.Assert.*
import org.junit.Test

class StorageIdentityTest {
    @Test
    fun logicalPathsRejectTraversalAbsolutePathsAndControlCharacters() {
        listOf("/absolute", "../book", "a/../book", "a/./book", "a//book", "a/", "C:/book",
            "a\\book", "a\u0000book", "a\nbook", "").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { RelativeSourcePath(path) }
        }
        assertEquals("作者/书名 (1)/书名.epub", RelativeSourcePath("作者/书名 (1)/书名.epub").value)
    }

    @Test
    fun formatsNormalizeWithoutAllowingPathFragments() {
        assertEquals(BookFormat.parse("EPUB"), BookFormat.parse("epub"))
        listOf(".epub", "../epub", "a/b", "a\\b", "epub\n", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { BookFormat.parse(it) }
        }
    }

    @Test
    fun columnsAreDiscoveredIdentitiesRatherThanSqlOrPaths() {
        assertEquals("#finished", CustomColumnId(2, "#finished").lookupName)
        listOf("read_status", "#../x", "#x;DROP TABLE", "#x\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { CustomColumnId(2, it) }
        }
    }
}
