package io.github.chenxiex.calibrecloud.state

import android.content.ContentValues
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.LibraryId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID

/** The book and format last handed to a reader, with the title shown for it. Not reading progress. */
data class LastOpened(val key: CopyKey, val title: String)

/** One record per library; replaceable in tests. */
interface LastOpenedStore {
    suspend fun get(libraryId: LibraryId): LastOpened?

    /** Replaces the record of the copy's library. Call only after the system accepted the open request. */
    suspend fun save(value: LastOpened)
}

/**
 * Last opened book per library binding. Clearing metadata, removing copies or clearing other
 * libraries' caches keeps it; it never implies that the book was read and never touches the source.
 */
class LastOpenedRepository(
    private val database: ApplicationStateDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : LastOpenedStore {
    override suspend fun get(libraryId: LibraryId): LastOpened? = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery(
            "SELECT source_id, source_uuid, format, title FROM last_opened WHERE library_id = ?",
            arrayOf(libraryId.value.toString()),
        ).use {
            if (!it.moveToFirst()) null else LastOpened(
                CopyKey(BookKey(libraryId, it.getLong(0), UUID.fromString(it.getString(1))), BookFormat.parse(it.getString(2))),
                it.getString(3),
            )
        }
    }

    override suspend fun save(value: LastOpened): Unit = withContext(ioDispatcher) {
        database.writableDatabase.insertWithOnConflict("last_opened", null, ContentValues().apply {
            put("library_id", value.key.book.libraryId.value.toString())
            put("source_id", value.key.book.sourceId)
            put("source_uuid", value.key.book.sourceUuid.toString())
            put("format", value.key.format.value)
            put("title", value.title)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }
}
