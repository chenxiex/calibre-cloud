package io.github.chenxiex.calibrecloud.metadata

import android.annotation.SuppressLint
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

enum class StagingFailure { LIBRARY_CHANGED, COLUMN_INVALID, INCOMPATIBLE, CORRUPT, NO_WRITABLE_BOOKS, INSUFFICIENT_SPACE, IO }
class ReadStatusStagingException(val reason: StagingFailure) : Exception(reason.name)

enum class StagedBookOutcome { CHANGED, ALREADY_TARGET, MISSING, IDENTITY_CHANGED }

/** A complete, closed and fsynced database file ready to be pushed; [sha256] is lowercase hex. */
data class StagedDatabase(val file: File, val sha256: String, val length: Long)

/** [staged] is null when every writable book already holds its target, so nothing must be pushed. */
data class StagedReadStatus(val books: Map<BookKey, StagedBookOutcome>, val staged: StagedDatabase?) {
    val hasChanges: Boolean get() = staged != null
}

/**
 * Builds the database to push for a read status write from the backend's latest private snapshot.
 * Never touches a storage backend, the derived index or the snapshot itself.
 *
 * The snapshot must parse with [CalibreSnapshotParser], carry the expected library UUID and still
 * define [CustomColumnId] as a single-valued bool column. Each book in the change list must keep its
 * numeric ID and UUID; missing or re-identified books are reported per book and never remapped.
 *
 * For every book whose value differs from its target (an absent value differs from "no"), one
 * transaction applies exactly what Calibre 9.14 `set_custom` was observed to change
 * (app/verification/phase-4.md): the `custom_column_N` row is replaced (new id, explicit 1 or 0,
 * `sqlite_sequence` bumped), `books.last_modified` is set to the injected UTC time in Calibre's
 * format and the book is added to `metadata_dirtied`. `books_update_trg` needs `title_sort` to
 * prepare; it is registered to fail if ever called because the title never changes.
 *
 * The result is then verified against the snapshot: header, schema, user_version and every
 * non-virtual table (FTS shadow tables included) must be identical apart from those rows, the file
 * must pass integrity_check and leave no journal. Table names in that comparison come from the
 * snapshot's own sqlite_master and are always quoted as identifiers. Any failure deletes the
 * partial file. The space check (twice the snapshot, for the copy and its rollback journal)
 * deliberately uses usableSpace: without allocateBytes, clearable cache is not free yet.
 */
@SuppressLint("UsableSpace")
class ReadStatusStaging(
    private val clock: Clock = Clock.systemUTC(),
    private val parser: CalibreSnapshotParser = CalibreSnapshotParser(),
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    fun stage(snapshot: File, output: File, libraryUuid: UUID?, column: CustomColumnId,
        changes: Map<BookKey, Boolean>, check: () -> Unit = {}): StagedReadStatus {
        require(changes.isNotEmpty())
        val parsed = try { parser.parse(snapshot) } catch (error: SnapshotParseException) {
            fail(when (error.reason) {
                SnapshotParseFailure.CORRUPT -> StagingFailure.CORRUPT
                SnapshotParseFailure.IO -> StagingFailure.IO
                SnapshotParseFailure.INCOMPATIBLE, SnapshotParseFailure.INVALID_METADATA -> StagingFailure.INCOMPATIBLE
            })
        }
        if (parsed.sourceLibraryUuid != libraryUuid) fail(StagingFailure.LIBRARY_CHANGED)
        val imported = parsed.columns.singleOrNull { it.id.sourceId == column.sourceId }
        if (imported == null || imported.id != column || imported.datatype != "bool" || imported.isMultiple) fail(StagingFailure.COLUMN_INVALID)
        val books = parsed.books.associateBy { it.sourceId }
        val outcomes = changes.mapValues { (key, target) ->
            val book = books[key.sourceId]
            when {
                book == null -> StagedBookOutcome.MISSING
                book.sourceUuid != key.sourceUuid -> StagedBookOutcome.IDENTITY_CHANGED
                (book.customValues[column.sourceId] as? ImportedColumnValue.Bool)?.value == target -> StagedBookOutcome.ALREADY_TARGET
                else -> StagedBookOutcome.CHANGED
            }
        }
        if (outcomes.values.none { it == StagedBookOutcome.CHANGED || it == StagedBookOutcome.ALREADY_TARGET }) fail(StagingFailure.NO_WRITABLE_BOOKS)
        val targets = changes.filterKeys { outcomes[it] == StagedBookOutcome.CHANGED }.mapKeys { it.key.sourceId }
        if (targets.isEmpty()) return StagedReadStatus(outcomes, null)
        if (!rollbackJournalHeader(snapshot)) fail(StagingFailure.INCOMPATIBLE)

        val directory = output.absoluteFile.parentFile ?: fail(StagingFailure.IO)
        if (availableBytes(directory) < snapshot.length() * 2) fail(StagingFailure.INSUFFICIENT_SPACE)
        val part = File(directory, "${output.name}.part")
        var published = false
        try {
            deleteWithJournals(part)
            copy(snapshot, part, check)
            val stamp = calibreTimestamp(clock.instant())
            open(part, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.setCustomScalarFunction("title_sort") { throw IllegalStateException("title_sort must not run for a read status write") }
                requireWritableLayout(db, column)
                val table = "custom_column_${column.sourceId}"
                db.beginTransaction()
                try {
                    targets.forEach { (book, target) ->
                        check()
                        db.execSQL("INSERT OR REPLACE INTO $table(book,value) VALUES(?,?)", arrayOf<Any>(book, if (target) 1L else 0L))
                        db.execSQL("UPDATE books SET last_modified=? WHERE id=?", arrayOf<Any>(stamp, book))
                        db.execSQL("INSERT OR IGNORE INTO metadata_dirtied(book) VALUES(?)", arrayOf<Any>(book))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            verifyReadStatusChanges(part, snapshot, column, targets, stamp)
            FileOutputStream(part, true).use { it.fd.sync() }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val length = part.length()
            deleteWithJournals(output)
            if (!part.renameTo(output)) fail(StagingFailure.IO)
            published = true
            return StagedReadStatus(outcomes, StagedDatabase(output, digest.digest().joinToString("") { "%02x".format(it) }, length))
        } catch (error: ReadStatusStagingException) {
            throw error
        } catch (_: SQLiteFullException) {
            fail(StagingFailure.INSUFFICIENT_SPACE)
        } catch (_: SQLiteException) {
            fail(StagingFailure.INCOMPATIBLE)
        } catch (error: IOException) {
            fail(if (error.message?.contains("ENOSPC") == true) StagingFailure.INSUFFICIENT_SPACE else StagingFailure.IO)
        } finally {
            if (!published) deleteWithJournals(part)
        }
    }

    private fun requireWritableLayout(db: SQLiteDatabase, column: CustomColumnId) {
        fun columns(table: String) = db.rawQuery("SELECT name FROM pragma_table_info(?)", arrayOf(table)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        if (!columns("books").contains("last_modified") || !columns("metadata_dirtied").contains("book") ||
            !columns("custom_column_${column.sourceId}").containsAll(setOf("id", "book", "value"))) fail(StagingFailure.INCOMPATIBLE)
    }

    private fun copy(source: File, target: File, check: () -> Unit) {
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        } }
    }
}

/**
 * Checks that [staged] differs from [base] only by the read status write described on
 * [ReadStatusStaging]: [targets] maps each changed book ID to its value and [stamp] is the
 * `last_modified` written for those books. Throws [ReadStatusStagingException] with CORRUPT for a
 * failed integrity check and INCOMPATIBLE for any other difference or leftover journal.
 */
internal fun verifyReadStatusChanges(staged: File, base: File, column: CustomColumnId, targets: Map<Long, Boolean>, stamp: String) {
    if (journals(staged).any { it.exists() }) fail(StagingFailure.INCOMPATIBLE)
    if (!sameHeader(staged, base)) fail(StagingFailure.INCOMPATIBLE)
    try {
        open(staged, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use {
                if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) fail(StagingFailure.CORRUPT)
            }
            db.execSQL("ATTACH DATABASE ? AS base", arrayOf(base.absolutePath))
            fun strings(sql: String) = db.rawQuery(sql, null).use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
            }
            fun exists(sql: String) = db.rawQuery("SELECT EXISTS ($sql)", null).use { it.moveToFirst() && it.getInt(0) == 1 }
            val schema = "SELECT type,name,tbl_name,sql FROM %s.sqlite_master ORDER BY type,name"
            if (strings(schema.format("main")) != strings(schema.format("base"))) fail(StagingFailure.INCOMPATIBLE)
            listOf("user_version", "application_id").forEach {
                if (strings("PRAGMA main.$it") != strings("PRAGMA base.$it")) fail(StagingFailure.INCOMPATIBLE)
            }
            val ids = targets.keys.joinToString(",", "(", ")")
            val readTable = "custom_column_${column.sourceId}"
            // A set difference both ways plus equal counts; values carry their storage class and compare binary.
            fun differs(table: String, columns: List<String>, filter: String = "1"): Boolean {
                val name = quote(table)
                val projection = columns.joinToString(",") { "typeof(${quote(it)}),${quote(it)} COLLATE BINARY" }
                val main = "SELECT $projection FROM main.$name WHERE $filter"
                val other = "SELECT $projection FROM base.$name WHERE $filter"
                return exists("$main EXCEPT $other") || exists("$other EXCEPT $main") ||
                    strings("SELECT (SELECT count(*) FROM main.$name WHERE $filter) = (SELECT count(*) FROM base.$name WHERE $filter)") != listOf(listOf("1"))
            }
            val tables = strings("SELECT name FROM main.sqlite_master WHERE type='table' AND sql NOT LIKE 'CREATE VIRTUAL TABLE%' ORDER BY name").map { it[0] }
            tables.forEach { table ->
                val columns = strings("SELECT name FROM pragma_table_info(${sqlString(table)}, 'main') ORDER BY cid").map { it[0] }
                if (columns.isEmpty()) fail(StagingFailure.INCOMPATIBLE)
                val unexpected = when (table) {
                    "books" -> differs(table, columns - "last_modified") || differs(table, listOf("id", "last_modified"), "id NOT IN $ids")
                    readTable -> differs(table, columns, "book NOT IN $ids")
                    "metadata_dirtied" -> exists("SELECT book FROM base.metadata_dirtied EXCEPT SELECT book FROM main.metadata_dirtied") ||
                        differs(table, columns, "book NOT IN $ids")
                    "sqlite_sequence" -> differs(table, columns, "name <> '$readTable'") ||
                        exists("SELECT 1 FROM base.sqlite_sequence b WHERE b.name='$readTable' AND NOT EXISTS " +
                            "(SELECT 1 FROM main.sqlite_sequence m WHERE m.name=b.name AND m.seq>=b.seq)")
                    else -> differs(table, columns)
                }
                if (unexpected) fail(StagingFailure.INCOMPATIBLE)
            }
            val written = db.rawQuery("SELECT b.id,c.value,b.last_modified,d.book IS NOT NULL FROM main.books b " +
                "JOIN main.$readTable c ON c.book=b.id LEFT JOIN main.metadata_dirtied d ON d.book=b.id WHERE b.id IN $ids", null).use { c ->
                buildMap { while (c.moveToNext()) {
                    if (c.getType(1) != Cursor.FIELD_TYPE_INTEGER || c.getString(2) != stamp || c.getInt(3) != 1) fail(StagingFailure.INCOMPATIBLE)
                    put(c.getLong(0), when (c.getLong(1)) { 1L -> true; 0L -> false; else -> fail(StagingFailure.INCOMPATIBLE) })
                } }
            }
            if (written != targets) fail(StagingFailure.INCOMPATIBLE)
        }
    } catch (error: ReadStatusStagingException) {
        throw error
    } catch (_: SQLiteException) {
        fail(StagingFailure.CORRUPT)
    }
    if (journals(staged).any { it.exists() }) fail(StagingFailure.INCOMPATIBLE)
}

/** Calibre stores `last_modified` as Python's `isoformat(' ')` in UTC: microseconds only when non-zero. */
internal fun calibreTimestamp(instant: Instant): String {
    val time = instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
    val seconds = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(time)
    val micros = time.nano / 1000
    return if (micros == 0) "$seconds+00:00" else String.format(Locale.ROOT, "%s.%06d+00:00", seconds, micros)
}

/**
 * Header bytes that a row-level write never changes: magic, page size, file format versions
 * (rollback journal = 1/1), reserved space, schema cookie, encoding, user version and application
 * ID. The change counter, page count, freelist and writer version legitimately change.
 */
private fun sameHeader(staged: File, base: File): Boolean {
    val a = header(staged) ?: return false
    val b = header(base) ?: return false
    return (0 until 24).all { a[it] == b[it] } && (40 until 92).all { a[it] == b[it] }
}

/** Only rollback-journal databases are staged: changing a WAL header would alter the file outside the write. */
private fun rollbackJournalHeader(file: File): Boolean {
    val bytes = header(file) ?: return false
    return bytes[18].toInt() == 1 && bytes[19].toInt() == 1
}

private fun header(file: File): ByteArray? = try {
    file.inputStream().use { input ->
        val bytes = ByteArray(100)
        var read = 0
        while (read < bytes.size) {
            val count = input.read(bytes, read, bytes.size - read)
            if (count < 0) return null
            read += count
        }
        bytes
    }
} catch (_: IOException) { null }

private fun open(file: File, mode: Int): SQLiteDatabase = SQLiteDatabase.openDatabase(file, SQLiteDatabase.OpenParams.Builder()
    // No android_metadata table, no localized collators and no deletion of a file deemed corrupt.
    .setOpenFlags(mode or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    .setJournalMode("DELETE").setSynchronousMode("FULL")
    .setErrorHandler(DatabaseErrorHandler { })
    .build())

private fun journals(file: File) = listOf("-journal", "-wal", "-shm").map { File(file.path + it) }
private fun deleteWithJournals(file: File) { (journals(file) + file).forEach { it.delete() } }
private fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""
private fun sqlString(value: String) = "'" + value.replace("'", "''") + "'"
private fun fail(reason: StagingFailure): Nothing = throw ReadStatusStagingException(reason)
