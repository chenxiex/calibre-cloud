package io.github.chenxiex.calibrecloud.metadata

import android.database.DatabaseErrorHandler
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.text.Html
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import java.io.File
import java.util.UUID
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** Source identities remain independent of application library/generation identities. */
data class ParsedLibrary(val sourceLibraryUuid: UUID?, val books: List<ImportedBook>, val columns: List<ImportedColumn>)
data class ImportedBook(
    val sourceId: Long,
    val sourceUuid: UUID,
    val title: String,
    val authors: List<String>,
    val addedAt: String?,
    val rating: Int?,
    val series: String?,
    val seriesIndex: Double?,
    val tags: List<String>,
    val commentsText: String,
    val formats: List<ImportedFormat>,
    val path: RelativeSourcePath,
    val hasCover: Boolean,
    val customValues: Map<Long, ImportedColumnValue>,
    /** Calibre's own books.last_modified text, compared verbatim; it changes when a format is replaced. */
    val lastModified: String? = null,
)
data class ImportedFormat(val format: BookFormat, val sizeBytes: Long?, val path: RelativeSourcePath)
data class ImportedColumn(val id: CustomColumnId, val name: String, val datatype: String, val isMultiple: Boolean, val supported: Boolean)
sealed interface ImportedColumnValue {
    data class Bool(val value: Boolean?) : ImportedColumnValue
    data class Text(val values: List<String>) : ImportedColumnValue
}
enum class SnapshotParseFailure { CORRUPT, INCOMPATIBLE, INVALID_METADATA, IO }
class SnapshotParseException(val reason: SnapshotParseFailure) : Exception(reason.name)

/**
 * Parses only the backend's completed private snapshot. Never migrates, repairs, checkpoints or
 * accesses a source directory. Capability checks follow Calibre 9.14.0's db/backend.py table
 * layout, including normalized text/enumeration and direct bool tables. Unknown datatypes are
 * listed but never evaluated. Queries use fixed names or checked numeric column IDs.
 */
class CalibreSnapshotParser {
    fun parse(file: File): ParsedLibrary {
        if (!file.isFile || !file.canRead()) throw SnapshotParseException(SnapshotParseFailure.IO)
        try {
            return SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use {
                    if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) fail(SnapshotParseFailure.CORRUPT)
                }
                parseDatabase(db)
            }
        } catch (error: SnapshotParseException) {
            throw error
        } catch (_: SQLiteException) {
            throw SnapshotParseException(SnapshotParseFailure.CORRUPT)
        } catch (_: NullPointerException) {
            throw SnapshotParseException(SnapshotParseFailure.INVALID_METADATA)
        } catch (_: IllegalArgumentException) {
            throw SnapshotParseException(SnapshotParseFailure.INVALID_METADATA)
        }
    }

    private fun parseDatabase(db: SQLiteDatabase): ParsedLibrary {
        val required = mapOf(
            "books" to "id uuid title timestamp path series_index has_cover",
            "authors" to "id name", "books_authors_link" to "id book author",
            "tags" to "id name", "books_tags_link" to "id book tag",
            "series" to "id name", "books_series_link" to "id book series",
            "ratings" to "id rating", "books_ratings_link" to "id book rating",
            "comments" to "book text", "data" to "book format uncompressed_size name",
            "custom_columns" to "id label name datatype is_multiple normalized mark_for_delete",
        )
        required.forEach { (table, columns) -> requireColumns(db, table, columns) }
        val libraryUuid = if (hasTable(db, "library_id")) {
            requireColumns(db, "library_id", "uuid")
            val ids = rows(db, "SELECT uuid FROM library_id") { uuid(it.getString(0)) }
            if (ids.size > 1) fail()
            ids.singleOrNull()
        } else null
        val bookIds = rows(db, "SELECT id FROM books") { it.getLong(0).also { id -> if (id <= 0) fail() } }.toSet()
        val authors = relation(db, bookIds, "authors", "author", "name")
        val tags = relation(db, bookIds, "tags", "tag", "name")
        val series = relation(db, bookIds, "series", "series", "name", single = true)
        val ratings = relation(db, bookIds, "ratings", "rating", "rating", single = true)
        val comments = mutableMapOf<Long, String>()
        rows(db, "SELECT book,text FROM comments") {
            val id = it.getLong(0)
            if (id !in bookIds || comments.containsKey(id)) fail()
            // Android's parser strips tags and decodes entities; scripts/styles are not searchable.
            val html = it.getString(1).replace(Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            comments[id] = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }
        val columnValues = mutableMapOf<Long, MutableMap<Long, ImportedColumnValue>>()
        val columns = rows(db, "SELECT id,label,name,datatype,is_multiple,normalized FROM custom_columns WHERE mark_for_delete=0 ORDER BY id") { c ->
            val id = CustomColumnId(c.getLong(0), "#${c.getString(1)}")
            val datatype = c.getString(3)
            val multiple = boolean(c, 4) ?: fail()
            val normalized = boolean(c, 5) ?: fail()
            val supported = datatype in setOf("bool", "text", "enumeration")
            if (supported) {
                if (normalized != (datatype != "bool") || (multiple && datatype != "text")) fail(SnapshotParseFailure.INCOMPATIBLE)
                val table = "custom_column_${id.sourceId}"
                if (normalized) {
                    requireColumns(db, table, "id value")
                    val linked = relation(db, bookIds, table, "value", "value", linkTable = "books_custom_column_${id.sourceId}_link", single = !multiple)
                    linked.forEach { (book, values) -> columnValues.getOrPut(book) { mutableMapOf() }[id.sourceId] = ImportedColumnValue.Text(values) }
                } else {
                    requireColumns(db, table, "book value")
                    rows(db, "SELECT book,value FROM $table") { value ->
                        val book = value.getLong(0)
                        val values = columnValues.getOrPut(book) { mutableMapOf() }
                        if (book !in bookIds || values.containsKey(id.sourceId)) fail()
                        values[id.sourceId] = ImportedColumnValue.Bool(boolean(value, 1))
                    }
                }
            }
            ImportedColumn(id, c.getString(2), datatype, multiple, supported)
        }
        if (columns.map { it.id.lookupName }.distinct().size != columns.size) fail()
        val rawFormats = rows(db, "SELECT book,format,uncompressed_size,name FROM data") { c ->
            val book = c.getLong(0)
            if (book !in bookIds) fail()
            val name = RelativeSourcePath(c.getString(3)).also { if ('/' in it.value) fail() }
            val size = if (c.isNull(2)) null else c.getLong(2).also { if (it < 0) fail() }
            Triple(book, BookFormat.parse(c.getString(1)), name.value to size)
        }.groupBy { it.first }
        val modified = if (rows(db, "PRAGMA table_info(books)") { it.getString(1) }.contains("last_modified")) "last_modified" else "NULL"
        val books = rows(db, "SELECT id,uuid,title,timestamp,path,series_index,has_cover,$modified FROM books ORDER BY id") { c ->
            val id = c.getLong(0)
            val path = RelativeSourcePath(c.getString(4))
            val formats = rawFormats[id].orEmpty().map { (_, format, data) ->
                ImportedFormat(format, data.second, RelativeSourcePath("${path.value}/${data.first}.${format.value.lowercase(java.util.Locale.ROOT)}"))
            }
            if (formats.map { it.format }.distinct().size != formats.size) fail()
            val rating = ratings[id]?.singleOrNull()?.toIntOrNull()?.also { if (it !in 0..10) fail() }
            if (ratings.containsKey(id) && rating == null) fail()
            val index = if (c.isNull(5)) null else c.getDouble(5).also { if (!it.isFinite()) fail() }
            ImportedBook(id, uuid(c.getString(1)), c.getString(2), authors[id].orEmpty().map { it.replace('|', ',') },
                timestamp(c.getString(3)), rating?.takeIf { it != 0 }, series[id]?.singleOrNull(), index,
                tags[id].orEmpty(), comments[id].orEmpty(), formats, path, boolean(c, 6) ?: false, columnValues[id].orEmpty(),
                if (c.isNull(7)) null else c.getString(7))
        }
        if (books.map { it.sourceUuid }.distinct().size != books.size) fail()
        return ParsedLibrary(libraryUuid, books, columns)
    }

    private fun relation(db: SQLiteDatabase, books: Set<Long>, table: String, foreignColumn: String, valueColumn: String,
        linkTable: String = "books_${table}_link", single: Boolean = false): Map<Long, List<String>> {
        requireColumns(db, linkTable, "id book $foreignColumn")
        val values = rows(db, "SELECT id,$valueColumn FROM $table") { it.getLong(0) to it.getString(1) }.toMap()
        val result = mutableMapOf<Long, MutableList<String>>()
        val seen = mutableSetOf<Pair<Long, Long>>()
        rows(db, "SELECT book,$foreignColumn FROM $linkTable ORDER BY id") {
            val book = it.getLong(0)
            val target = it.getLong(1)
            if (book !in books || target !in values || !seen.add(book to target)) fail()
            val list = result.getOrPut(book) { mutableListOf() }
            if (single && list.isNotEmpty()) fail()
            list.add(values.getValue(target))
        }
        return result
    }

    private fun requireColumns(db: SQLiteDatabase, table: String, names: String) {
        if (!hasTable(db, table)) fail(SnapshotParseFailure.INCOMPATIBLE)
        val actual = rows(db, "PRAGMA table_info($table)") { it.getString(1) }.toSet()
        if (!actual.containsAll(names.split(' '))) fail(SnapshotParseFailure.INCOMPATIBLE)
    }
    private fun hasTable(db: SQLiteDatabase, table: String) = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
    private fun <T> rows(db: SQLiteDatabase, sql: String, read: (Cursor) -> T): List<T> = db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    private fun boolean(c: Cursor, index: Int): Boolean? = if (c.isNull(index)) null else when (c.getString(index)) { "0" -> false; "1" -> true; else -> fail() }
    private fun uuid(value: String?): UUID {
        if (value == null || !value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) fail()
        return UUID.fromString(value)
    }
    private fun timestamp(value: String?): String? {
        if (value == null) return null
        val iso = value.replace(' ', 'T')
        try {
            OffsetDateTime.parse(iso)
        } catch (_: DateTimeParseException) {
            try { LocalDateTime.parse(iso) } catch (_: DateTimeParseException) { fail() }
        }
        return value
    }
    private fun fail(reason: SnapshotParseFailure = SnapshotParseFailure.INVALID_METADATA): Nothing = throw SnapshotParseException(reason)
}
