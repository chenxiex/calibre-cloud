package io.github.chenxiex.calibrecloud.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android SQLite in UUID-named private test databases. */
@RunWith(AndroidJUnit4::class)
class SearchHistoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var history: SearchHistoryRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "history-test-${UUID.randomUUID()}.db"
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun reopen(limit: Int = SearchHistoryRepository.DEFAULT_LIMIT) {
        database = ApplicationStateDatabase(context, name)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        history = SearchHistoryRepository(database, Dispatchers.IO, limit)
    }

    private suspend fun bound(root: String): LibraryId {
        val identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", root), UUID.randomUUID())
        assertTrue(state.bindValidated(state.select(identity.location).token, identity))
        return identity.id
    }

    @Test
    fun executedQueriesPersistNewestFirstPerLibraryAndClearOnlyTheirOwn() = runBlocking<Unit> {
        val first = bound("first")
        val second = bound("second")
        history.record(first, "  三体 ")
        history.record(first, "刘慈欣")
        history.record(first, "三体")
        history.record(first, "   ")
        history.record(second, "other")

        database.close()
        reopen()
        // Repeating a query moves it to the front; blank input is never saved.
        assertEquals(listOf("三体", "刘慈欣"), history.list(first))
        assertEquals(listOf("other"), history.list(second))

        history.clear(first)
        assertEquals(emptyList<String>(), history.list(first))
        assertEquals(listOf("other"), history.list(second))
        assertEquals(second, state.current()?.identity?.id)
    }

    @Test
    fun onlyTheNewestEntriesAreKept() = runBlocking<Unit> {
        database.close()
        reopen(limit = 3)
        val library = bound("limited")
        (1..5).forEach { history.record(library, "q$it") }
        assertEquals(listOf("q5", "q4", "q3"), history.list(library))
    }

    @Test
    fun versionSixMigrationAddsAnEmptyHistoryAndKeepsSettings() = runBlocking<Unit> {
        val library = bound("migrated")
        state.setStartupEnabled(true)
        StateSchemaHistory.downgrade(database.writableDatabase, 6)
        database.close()
        reopen()
        assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
        assertEquals(library, state.current()?.identity?.id)
        assertTrue(state.startupEnabled())
        assertEquals(emptyList<String>(), history.list(library))
        history.record(library, "after")
        assertEquals(listOf("after"), history.list(library))
    }
}
