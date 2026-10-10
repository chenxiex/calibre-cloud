package io.github.chenxiex.calibrecloud.metadata

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.LibraryId
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on the repository's Calibre 9.14 sample (FTS5 tables included) with a second bool column and
 * a quoted Chinese title added. Results of the read and unread runs are copied to the debug app's
 * external files directory `read-status-staging/` so they can be pulled and checked with calibredb
 * and app/verification/tools/calibre_db_diff.py.
 */
@RunWith(AndroidJUnit4::class)
class ReadStatusStagingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "read-status-staging-${UUID.randomUUID()}").apply { mkdirs() }
    private val library = LibraryId(UUID.randomUUID())
    private val libraryUuid = UUID.fromString("68bd6f1d-8a36-487a-aff3-f122dbc1f215")
    private val column = CustomColumnId(1, "#read_status")
    private val guide = BookKey(library, 1, UUID.fromString("ed5e903d-83cb-418c-bbd3-90de61ff46c9")) // absent value
    private val hamlet = BookKey(library, 4, UUID.fromString("8a4e0ccb-7293-4c3a-bc58-3b130d950359")) // yes
    private val lear = BookKey(library, 5, UUID.fromString("c1e49ea8-507e-4f67-ad93-6249aa9d6d68")) // no
    private val instant = Instant.parse("2026-10-10T08:09:10.123456789Z")
    private val stamp = "2026-10-10 08:09:10.123456+00:00"
    private val title = "It's \"引号\" 书"
    private val staging = ReadStatusStaging(Clock.fixed(instant, ZoneOffset.UTC))

    @After fun cleanUp() { directory.deleteRecursively() }

    @Test fun marksReadOnlyChangingTargetRowsLastModifiedDirtiedAndSequence() {
        val base = snapshot()
        val before = base.readBytes()
        val baseModified = lastModified(base)
        val output = File(directory, "staged.db")
        val result = staging.stage(base, output, libraryUuid, column, mapOf(guide to true, hamlet to true, lear to true))
        assertEquals(mapOf(guide to StagedBookOutcome.CHANGED, hamlet to StagedBookOutcome.ALREADY_TARGET, lear to StagedBookOutcome.CHANGED), result.books)
        val staged = result.staged!!
        assertEquals(output, staged.file)
        assertEquals(output.length(), staged.length)
        assertEquals(sha256(output), staged.sha256)
        assertArrayEquals(before, base.readBytes())
        assertEquals(listOf("staged.db"), directory.list()!!.filter { it.startsWith("staged") })

        val parsed = CalibreSnapshotParser().parse(output)
        val values = parsed.books.associate { it.sourceId to it.customValues }
        assertEquals(mapOf(1L to true, 4L to true, 5L to true), values.mapValues { (it.value[1] as ImportedColumnValue.Bool).value })
        assertEquals(ImportedColumnValue.Bool(true), values.getValue(1)[2])
        assertEquals(ImportedColumnValue.Bool(false), values.getValue(4)[2])
        assertNull(values.getValue(5)[2])
        assertEquals(title, parsed.books.first { it.sourceId == 1L }.title)
        assertEquals(baseModified + mapOf(1L to stamp, 5L to stamp), lastModified(output))
        assertEquals(listOf(1L, 5L), longs(output, "SELECT book FROM metadata_dirtied ORDER BY book"))
        assertEquals(sequence(base) + mapOf("custom_column_1" to sequence(base).getValue("custom_column_1") + 2), sequence(output))
        export(base, "base.db")
        export(output, "mark-read.db")
    }

    @Test fun marksUnreadWithExplicitNoIncludingAnAbsentValue() {
        val base = snapshot()
        val output = File(directory, "staged.db")
        val result = staging.stage(base, output, libraryUuid, column, mapOf(guide to false, hamlet to false, lear to false))
        assertEquals(mapOf(guide to StagedBookOutcome.CHANGED, hamlet to StagedBookOutcome.CHANGED, lear to StagedBookOutcome.ALREADY_TARGET), result.books)
        query(output, "SELECT book,typeof(value),value FROM custom_column_1 ORDER BY book") { c ->
            assertEquals(listOf("1 integer 0", "4 integer 0", "5 integer 0"), rows(c) { "${it.getLong(0)} ${it.getString(1)} ${it.getLong(2)}" })
        }
        assertEquals(listOf(1L, 4L), longs(output, "SELECT book FROM metadata_dirtied ORDER BY book"))
        export(output, "mark-unread.db")
    }

    @Test fun mixedTargetsInOneListAreAppliedTogether() {
        val output = File(directory, "staged.db")
        val result = staging.stage(snapshot(), output, libraryUuid, column, mapOf(guide to true, hamlet to false))
        assertTrue(result.books.values.all { it == StagedBookOutcome.CHANGED })
        assertEquals(listOf(1L to 1L, 4L to 0L, 5L to 0L), pairs(output, "SELECT book,value FROM custom_column_1 ORDER BY book"))
    }

    @Test fun alreadySatisfiedListProducesNothingToPush() {
        val output = File(directory, "staged.db")
        val result = staging.stage(snapshot(), output, libraryUuid, column, mapOf(hamlet to true, lear to false))
        assertFalse(result.hasChanges)
        assertNull(result.staged)
        assertEquals(setOf(StagedBookOutcome.ALREADY_TARGET), result.books.values.toSet())
        assertFalse(output.exists())
        assertFalse(File(directory, "staged.db.part").exists())
    }

    @Test fun changedIdentitiesAndDeletedBooksAreReportedAndNeverRemapped() {
        val output = File(directory, "staged.db")
        val renamed = guide.copy(sourceUuid = UUID.randomUUID())
        val deleted = BookKey(library, 99, UUID.randomUUID())
        val result = staging.stage(snapshot(), output, libraryUuid, column, mapOf(renamed to true, deleted to true, lear to true))
        assertEquals(mapOf(renamed to StagedBookOutcome.IDENTITY_CHANGED, deleted to StagedBookOutcome.MISSING, lear to StagedBookOutcome.CHANGED), result.books)
        assertEquals(listOf(4L to 1L, 5L to 1L), pairs(output, "SELECT book,value FROM custom_column_1 ORDER BY book"))
        assertEquals(listOf(5L), longs(output, "SELECT book FROM metadata_dirtied"))
        assertStagingFails(StagingFailure.NO_WRITABLE_BOOKS) { staging.stage(snapshot(), output, libraryUuid, column, mapOf(renamed to true, deleted to false)) }
    }

    @Test fun invalidColumnsLibrariesAndFilesAreRejectedWithoutOutput() {
        listOf(
            "UPDATE custom_columns SET label='done' WHERE id=1" to StagingFailure.COLUMN_INVALID,
            "UPDATE custom_columns SET mark_for_delete=1 WHERE id=1" to StagingFailure.COLUMN_INVALID,
            "DELETE FROM custom_columns WHERE id=1" to StagingFailure.COLUMN_INVALID,
            "UPDATE custom_columns SET datatype='int' WHERE id=1" to StagingFailure.COLUMN_INVALID,
            "UPDATE library_id SET uuid='${UUID.randomUUID()}'" to StagingFailure.LIBRARY_CHANGED,
        ).forEach { (mutation, expected) ->
            val base = snapshot()
            writable(base) { it.execSQL(mutation) }
            assertStagingFails(expected) { staging.stage(base, File(directory, "staged.db"), libraryUuid, column, mapOf(guide to true)) }
        }
        val corrupt = File(directory, "corrupt.db").apply { writeBytes(snapshot().readBytes().copyOf(8192)) }
        assertStagingFails(StagingFailure.CORRUPT) { staging.stage(corrupt, File(directory, "staged.db"), libraryUuid, column, mapOf(guide to true)) }
        assertStagingFails(StagingFailure.INSUFFICIENT_SPACE) {
            ReadStatusStaging(availableBytes = { 0 }).stage(snapshot(), File(directory, "staged.db"), libraryUuid, column, mapOf(guide to true))
        }
        assertEquals(emptyList<String>(), directory.list()!!.filter { it.startsWith("staged") })
    }

    @Test fun scopeVerificationRejectsAnyChangeBeyondTheWrite() {
        val base = snapshot()
        val output = File(directory, "staged.db")
        staging.stage(base, output, libraryUuid, column, mapOf(guide to true))
        val targets = mapOf(1L to true)
        verifyReadStatusChanges(output, base, column, targets, stamp)
        listOf(
            "UPDATE custom_column_2 SET value=0 WHERE book=1",
            "UPDATE books SET has_cover=0 WHERE id=1",
            "UPDATE books SET last_modified='$stamp' WHERE id=4",
            "INSERT INTO metadata_dirtied(book) VALUES(4)",
            "UPDATE custom_column_1 SET value='yes' WHERE book=1",
        ).forEachIndexed { index, mutation ->
            val tampered = File(directory, "tampered-$index.db").apply { writeBytes(output.readBytes()) }
            writable(tampered) { it.execSQL(mutation) }
            assertStagingFails(StagingFailure.INCOMPATIBLE) { verifyReadStatusChanges(tampered, base, column, targets, stamp) }
        }
        assertStagingFails(StagingFailure.INCOMPATIBLE) { verifyReadStatusChanges(output, base, column, mapOf(1L to false), stamp) }
        assertEquals("2026-10-10 08:09:10+00:00", calibreTimestamp(Instant.parse("2026-10-10T08:09:10.000000999Z")))
    }

    /** The sample plus a second bool column built from Calibre's own DDL and a title needing quoting. */
    private fun snapshot(): File {
        val file = File(directory, "snapshot-${UUID.randomUUID()}.db")
        InstrumentationRegistry.getInstrumentation().context.assets.open("calibre-sample/metadata.db").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        writable(file) { db ->
            db.execSQL("INSERT INTO custom_columns(id,label,name,datatype,mark_for_delete,editable,display,is_multiple,normalized) " +
                "SELECT 2,'favorite','收藏',datatype,mark_for_delete,editable,display,is_multiple,normalized FROM custom_columns WHERE id=1")
            val ddl = db.rawQuery("SELECT sql FROM sqlite_master WHERE tbl_name='custom_column_1' AND sql IS NOT NULL ORDER BY type<>'table'", null).use { c -> rows(c) { it.getString(0) } }
            ddl.forEach { db.execSQL(it.replace("custom_column_1", "custom_column_2")) }
            db.execSQL("INSERT INTO custom_column_2(book,value) VALUES(1,1),(4,0)")
            db.execSQL("UPDATE books SET title=? WHERE id=1", arrayOf(title))
        }
        return file
    }

    /** books_update_trg needs title_sort to prepare any UPDATE of books; an identity stands in for Calibre's. */
    private fun writable(file: File, block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(file, SQLiteDatabase.OpenParams.Builder()
        .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).setJournalMode("DELETE").build())
        .use { db -> db.setCustomScalarFunction("title_sort") { it }; block(db) }
    private fun <T> query(file: File, sql: String, read: (Cursor) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> db.rawQuery(sql, null).use(read) }
    private fun <T> rows(c: Cursor, read: (Cursor) -> T) = buildList { while (c.moveToNext()) add(read(c)) }
    private fun longs(file: File, sql: String) = query(file, sql) { c -> rows(c) { it.getLong(0) } }
    private fun pairs(file: File, sql: String) = query(file, sql) { c -> rows(c) { it.getLong(0) to it.getLong(1) } }
    private fun lastModified(file: File) = query(file, "SELECT id,last_modified FROM books") { c -> rows(c) { it.getLong(0) to it.getString(1) }.toMap() }
    private fun sequence(file: File) = query(file, "SELECT name,seq FROM sqlite_sequence") { c -> rows(c) { it.getString(0) to it.getLong(1) }.toMap() }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun export(file: File, name: String) {
        val target = File(context.getExternalFilesDir("read-status-staging"), name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }
    private fun assertStagingFails(expected: StagingFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (error: ReadStatusStagingException) { assertEquals(expected, error.reason) }
    }
}
