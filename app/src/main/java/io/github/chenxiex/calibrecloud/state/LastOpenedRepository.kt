package io.github.chenxiex.calibrecloud.state

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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

    /**
     * Replaces the record of the copy's library and records the copy under [readerName], the display name
     * the reader was given (R29, R37). Call only after the system accepted the open request.
     */
    suspend fun save(value: LastOpened, readerName: String)
}

/**
 * Last opened book per library binding, and every copy handed to a reader by its display name (R37).
 * Clearing metadata, removing copies or clearing other libraries' caches keeps both; removing the
 * library deletes them. Neither implies that the book was read, and neither touches the source.
 */
class LastOpenedRepository(
    private val database: ApplicationStateDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
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

    override suspend fun save(value: LastOpened, readerName: String): Unit = withContext(ioDispatcher) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("last_opened", null, ContentValues().apply {
                put("library_id", value.key.book.libraryId.value.toString())
                put("source_id", value.key.book.sourceId)
                put("source_uuid", value.key.book.sourceUuid.toString())
                put("format", value.key.format.value)
                put("title", value.title)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("opened_copies", null, ContentValues().apply {
                put("library_id", value.key.book.libraryId.value.toString())
                put("reader_name", readerName)
                put("source_id", value.key.book.sourceId)
                put("source_uuid", value.key.book.sourceUuid.toString())
                put("format", value.key.format.value)
                put("opened_at", clock())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The copies handed to a reader as [readerName], in any library (R37). A library keeps one per name,
     * the latest; the caller decides what a record of another library means.
     */
    suspend fun opened(readerName: String): List<CopyKey> = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery(
            "SELECT library_id, source_id, source_uuid, format FROM opened_copies WHERE reader_name = ?", arrayOf(readerName),
        ).use {
            buildList {
                while (it.moveToNext()) add(CopyKey(BookKey(LibraryId(UUID.fromString(it.getString(0))), it.getLong(1),
                    UUID.fromString(it.getString(2))), BookFormat.parse(it.getString(3))))
            }
        }
    }
}
