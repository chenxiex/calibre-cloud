package io.github.chenxiex.calibrecloud.metadata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalibreSnapshotParserTest {
    @Test fun parsesRepositoryCalibreSnapshotIncludingFtsSchemaWithoutChangingBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "calibre-sample-${UUID.randomUUID()}").apply { mkdirs() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val original = assets.open("calibre-sample/metadata.db").use { it.readBytes() }
        try {
            val file = File(directory, "metadata.db").apply { writeBytes(original) }
            val parsed = CalibreSnapshotParser().parse(file)
            assertEquals(UUID.fromString("68bd6f1d-8a36-487a-aff3-f122dbc1f215"), parsed.sourceLibraryUuid)
            assertEquals(listOf(1L, 4L, 5L), parsed.books.map { it.sourceId })
            val book = parsed.books.first()
            assertEquals(1L, book.sourceId)
            assertEquals(UUID.fromString("ed5e903d-83cb-418c-bbd3-90de61ff46c9"), book.sourceUuid)
            assertEquals("Quick Start Guide", book.title)
            assertEquals(listOf("John Schember"), book.authors)
            assertEquals("EPUB", book.formats.single().format.value)
            assertEquals(51734L, book.formats.single().sizeBytes)
            assertEquals("John Schember/Quick Start Guide (1)/Quick Start Guide - John Schember.epub", book.formats.single().path.value)
            val column = parsed.columns.single()
            assertEquals(1L, column.id.sourceId)
            assertEquals("#read_status", column.id.lookupName)
            assertEquals("阅读状态", column.name)
            assertEquals("bool", column.datatype)
            assertTrue(column.supported)
            assertFalse(column.isMultiple)
            // Desktop sample has an absent value, an explicit yes and an explicit no.
            assertFalse(book.customValues.containsKey(column.id.sourceId))
            val hamlet = parsed.books[1]
            assertEquals("哈姆莱特", hamlet.title)
            assertEquals(UUID.fromString("8a4e0ccb-7293-4c3a-bc58-3b130d950359"), hamlet.sourceUuid)
            assertEquals(listOf("[英]莎士比亚 著"), hamlet.authors)
            assertTrue(hamlet.tags.isEmpty())
            assertNull(hamlet.series)
            assertEquals(ImportedColumnValue.Bool(true), hamlet.customValues[column.id.sourceId])
            val lear = parsed.books[2]
            assertEquals("李尔王", lear.title)
            assertEquals(UUID.fromString("c1e49ea8-507e-4f67-ad93-6249aa9d6d68"), lear.sourceUuid)
            assertEquals(listOf("[英]莎士比亚 著/朱生豪 译"), lear.authors)
            assertEquals(ImportedColumnValue.Bool(false), lear.customValues[column.id.sourceId])
            assertEquals(listOf(51734L, 254235L, 245918L), parsed.books.map { it.formats.single().sizeBytes })
            parsed.books.forEach {
                assertEquals("EPUB", it.formats.single().format.value)
                assertTrue(it.hasCover)
                assertNotNull(it.addedAt)
                assertNull(it.rating)
                assertTrue(it.formats.single().path.value.startsWith("${it.path.value}/"))
            }
            assertEquals(parsed, parsedLibraryFromJson(parsed.toJson()))
            assertArrayEquals(original, file.readBytes())
            assertArrayEquals(original, assets.open("calibre-sample/metadata.db").use { it.readBytes() })
        } finally { directory.deleteRecursively() }
    }

    @Test fun parsesRelationsDynamicColumnsFormatsAndRoundTripsWithoutChangingSnapshot() = withFixture { file ->
        val bytes = file.readBytes()
        val parsed = CalibreSnapshotParser().parse(file)
        val book = parsed.books.single()
        assertEquals(listOf("作者,姓名"), book.authors)
        assertEquals(listOf("标签甲", "标签乙"), book.tags)
        assertEquals("2026-01-02 03:04:05+00:00", book.addedAt)
        assertEquals(8, book.rating)
        assertEquals("丛书", book.series)
        assertEquals(2.5, book.seriesIndex!!, 0.0)
        assertEquals("说明 & 简介", book.commentsText)
        assertEquals(listOf(42L, null), book.formats.map { it.sizeBytes })
        assertEquals("作者/书名 (1)/正文.epub", book.formats.first().path.value)
        assertEquals("#finished", parsed.columns.first().id.lookupName)
        assertEquals(ImportedColumnValue.Bool(true), book.customValues[1])
        assertEquals(ImportedColumnValue.Text(listOf("单值")), book.customValues[2])
        assertEquals(ImportedColumnValue.Text(listOf("甲", "乙")), book.customValues[3])
        assertEquals(ImportedColumnValue.Text(listOf("收藏")), book.customValues[4])
        assertFalse(parsed.columns.last().supported)
        assertFalse(book.customValues.containsKey(5))
        assertEquals(parsed, parsedLibraryFromJson(parsed.toJson()))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun rejectsUnsafePathsMissingCapabilitiesAndDanglingRelations() {
        listOf(
            "UPDATE books SET path='../escape'" to SnapshotParseFailure.INVALID_METADATA,
            "UPDATE data SET name='../../escape'" to SnapshotParseFailure.INVALID_METADATA,
            "UPDATE data SET format='EPUB/../../x'" to SnapshotParseFailure.INVALID_METADATA,
            "UPDATE books SET timestamp='invalid-date'" to SnapshotParseFailure.INVALID_METADATA,
            "UPDATE books SET uuid='1-1-1-1-1'" to SnapshotParseFailure.INVALID_METADATA,
            "UPDATE books_authors_link SET author=999" to SnapshotParseFailure.INVALID_METADATA,
            "UPDATE custom_columns SET label='bad-name' WHERE id=1" to SnapshotParseFailure.INVALID_METADATA,
            "UPDATE custom_columns SET normalized=1 WHERE id=1" to SnapshotParseFailure.INCOMPATIBLE,
            "DROP TABLE books_tags_link" to SnapshotParseFailure.INCOMPATIBLE,
            "UPDATE custom_column_1 SET value=2" to SnapshotParseFailure.INVALID_METADATA,
        ).forEach { (mutation, expected) -> withFixture { file ->
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(mutation) }
            val before = file.readBytes()
            try {
                CalibreSnapshotParser().parse(file)
                fail("Expected rejection for $mutation")
            } catch (error: SnapshotParseException) { assertEquals(expected, error.reason) }
            assertArrayEquals(before, file.readBytes())
        } }
    }

    @Test fun missingBooleanValueRemainsEmptyAndZeroRatingIsUnrated() = withFixture { file ->
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM custom_column_1")
            it.execSQL("UPDATE ratings SET rating=0")
        }
        val book = CalibreSnapshotParser().parse(file).books.single()
        assertFalse(book.customValues.containsKey(1))
        assertNull(book.rating)
    }

    @Test fun explicitFalseIsPreservedAndNullBooleanIsDistinct() = withFixture { file ->
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE custom_column_1 SET value=0")
        }
        assertEquals(ImportedColumnValue.Bool(false), CalibreSnapshotParser().parse(file).books.single().customValues[1])
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE custom_column_1 SET value=NULL")
        }
        assertEquals(ImportedColumnValue.Bool(null), CalibreSnapshotParser().parse(file).books.single().customValues[1])
    }

    @Test fun corruptSnapshotIsRejectedWithoutDeletion() = withFixture { file ->
        file.writeText("invalid SQLite")
        try {
            CalibreSnapshotParser().parse(file)
            fail("Expected corrupt rejection")
        } catch (error: SnapshotParseException) { assertEquals(SnapshotParseFailure.CORRUPT, error.reason) }
        assertEquals("invalid SQLite", file.readText())
    }

    private fun withFixture(test: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "parser-${UUID.randomUUID()}").apply { mkdirs() }
        try { test(CalibreFixture.create(File(directory, "metadata.db"))) }
        finally { directory.deleteRecursively() }
    }
}
