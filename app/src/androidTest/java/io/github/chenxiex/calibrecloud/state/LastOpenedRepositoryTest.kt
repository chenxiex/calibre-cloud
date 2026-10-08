package io.github.chenxiex.calibrecloud.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android SQLite in UUID-named private test databases. */
@RunWith(AndroidJUnit4::class)
class LastOpenedRepositoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var records: LastOpenedRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "last-opened-test-${UUID.randomUUID()}.db"
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, name)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        records = LastOpenedRepository(database, Dispatchers.IO)
    }

    private suspend fun bound(root: String): LibraryId {
        val identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", root), UUID.randomUUID())
        assertTrue(state.bindValidated(state.select(identity.location).token, identity))
        return identity.id
    }

    private fun opened(library: LibraryId, id: Long, format: String, title: String) =
        LastOpened(CopyKey(BookKey(library, id, UUID(0, id)), BookFormat.parse(format)), title)

    @Test
    fun eachLibraryKeepsOnlyItsNewestRecordAcrossRestarts() = runBlocking<Unit> {
        val first = bound("first")
        val second = bound("second")
        records.save(opened(first, 1, "EPUB", "旧书"))
        records.save(opened(first, 2, "PDF", "新书"))
        records.save(opened(second, 1, "EPUB", "other"))

        database.close()
        reopen()
        // Same numeric book ID in another library stays separate.
        assertEquals(opened(first, 2, "PDF", "新书"), records.get(first))
        assertEquals(opened(second, 1, "EPUB", "other"), records.get(second))
        assertNull(records.get(LibraryId(UUID.randomUUID())))
    }

    @Test
    fun versionSevenMigrationAddsAnEmptyRecordAndKeepsHistory() = runBlocking<Unit> {
        val library = bound("migrated")
        SearchHistoryRepository(database, Dispatchers.IO).record(library, "kept")
        StateSchemaHistory.downgrade(database.writableDatabase, 7)
        database.close()
        reopen()
        assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
        assertEquals(listOf("kept"), SearchHistoryRepository(database, Dispatchers.IO).list(library))
        assertNull(records.get(library))
        records.save(opened(library, 3, "EPUB", "after"))
        assertEquals(opened(library, 3, "EPUB", "after"), records.get(library))
    }
}
