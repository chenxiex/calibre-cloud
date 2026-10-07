package io.github.chenxiex.calibrecloud.state

import android.content.ContentValues
import io.github.chenxiex.calibrecloud.model.LibraryId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Search history of one library, newest first; replaceable in tests. */
interface SearchHistoryStore {
    suspend fun list(libraryId: LibraryId): List<String>

    /** Saves an executed query; repeating one moves it to the front. Blank queries are ignored. */
    suspend fun record(libraryId: LibraryId, query: String)

    suspend fun clear(libraryId: LibraryId)
}

/**
 * Executed queries per library binding. Clearing metadata, copies or other libraries' caches keeps
 * them; [clear] removes only this library's history and never touches the source library. The query
 * text stays in the private database and must not be logged.
 */
class SearchHistoryRepository(
    private val database: ApplicationStateDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val limit: Int = DEFAULT_LIMIT,
) : SearchHistoryStore {
    override suspend fun list(libraryId: LibraryId): List<String> = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery(
            "SELECT query FROM search_history WHERE library_id = ? ORDER BY sequence DESC LIMIT ?",
            arrayOf(libraryId.value.toString(), limit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    override suspend fun record(libraryId: LibraryId, query: String): Unit = withContext(ioDispatcher) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext
        val id = libraryId.value.toString()
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val next = db.rawQuery("SELECT COALESCE(MAX(sequence), 0) + 1 FROM search_history WHERE library_id = ?", arrayOf(id))
                .use { it.moveToFirst(); it.getLong(0) }
            db.insertWithOnConflict("search_history", null, ContentValues().apply {
                put("library_id", id); put("query", text); put("sequence", next)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            // Only the newest entries are kept.
            db.execSQL("""DELETE FROM search_history WHERE library_id = ? AND sequence NOT IN
                (SELECT sequence FROM search_history WHERE library_id = ? ORDER BY sequence DESC LIMIT ?)""",
                arrayOf(id, id, limit))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun clear(libraryId: LibraryId): Unit = withContext(ioDispatcher) {
        database.writableDatabase.delete("search_history", "library_id = ?", arrayOf(libraryId.value.toString()))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}
