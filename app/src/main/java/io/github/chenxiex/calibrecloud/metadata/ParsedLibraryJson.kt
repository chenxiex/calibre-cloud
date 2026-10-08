package io.github.chenxiex.calibrecloud.metadata

import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Private derived-index representation; never uploaded to a Calibre source. */
fun ParsedLibrary.toJson(): String = JSONObject().apply {
    put("libraryUuid", sourceLibraryUuid?.toString() ?: JSONObject.NULL)
    put("columns", JSONArray(columns.map { c -> JSONObject().apply {
        put("id", c.id.sourceId); put("lookup", c.id.lookupName); put("name", c.name)
        put("datatype", c.datatype); put("multiple", c.isMultiple); put("supported", c.supported)
    } }))
    put("books", JSONArray(books.map { b -> JSONObject().apply {
        put("id", b.sourceId); put("uuid", b.sourceUuid.toString()); put("title", b.title)
        put("authors", JSONArray(b.authors)); put("addedAt", b.addedAt ?: JSONObject.NULL)
        put("rating", b.rating ?: JSONObject.NULL); put("series", b.series ?: JSONObject.NULL)
        put("seriesIndex", b.seriesIndex ?: JSONObject.NULL); put("tags", JSONArray(b.tags))
        put("comments", b.commentsText); put("path", b.path.value); put("cover", b.hasCover)
        put("modified", b.lastModified ?: JSONObject.NULL)
        put("formats", JSONArray(b.formats.map { f -> JSONObject().apply {
            put("format", f.format.value); put("size", f.sizeBytes ?: JSONObject.NULL); put("path", f.path.value)
        } }))
        put("values", JSONArray(b.customValues.map { (id, value) -> JSONObject().apply {
            put("id", id)
            when (value) {
                is ImportedColumnValue.Bool -> { put("type", "bool"); put("value", value.value ?: JSONObject.NULL) }
                is ImportedColumnValue.Text -> { put("type", "text"); put("value", JSONArray(value.values)) }
            }
        } }))
    } }))
}.toString()

fun parsedLibraryFromJson(raw: String): ParsedLibrary {
    val root = JSONObject(raw)
    val columns = root.getJSONArray("columns").objects().map { c ->
        ImportedColumn(CustomColumnId(c.getLong("id"), c.getString("lookup")), c.getString("name"),
            c.getString("datatype"), c.getBoolean("multiple"), c.getBoolean("supported"))
    }
    val books = root.getJSONArray("books").objects().map { b ->
        ImportedBook(b.getLong("id"), UUID.fromString(b.getString("uuid")), b.getString("title"),
            b.getJSONArray("authors").strings(), b.nullString("addedAt"),
            if (b.isNull("rating")) null else b.getInt("rating"), b.nullString("series"),
            if (b.isNull("seriesIndex")) null else b.getDouble("seriesIndex"), b.getJSONArray("tags").strings(),
            b.getString("comments"), b.getJSONArray("formats").objects().map { f ->
                ImportedFormat(BookFormat.parse(f.getString("format")), if (f.isNull("size")) null else f.getLong("size"), RelativeSourcePath(f.getString("path")))
            }, RelativeSourcePath(b.getString("path")), b.getBoolean("cover"),
            b.getJSONArray("values").objects().associate { v ->
                v.getLong("id") to when (v.getString("type")) {
                    "bool" -> ImportedColumnValue.Bool(if (v.isNull("value")) null else v.getBoolean("value"))
                    "text" -> ImportedColumnValue.Text(v.getJSONArray("value").strings())
                    else -> throw IllegalArgumentException("Unsupported derived value")
                }
            }, if (b.has("modified")) b.nullString("modified") else null)
    }
    return ParsedLibrary(root.nullString("libraryUuid")?.let(UUID::fromString), books, columns)
}
private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings() = (0 until length()).map(::getString)
private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
