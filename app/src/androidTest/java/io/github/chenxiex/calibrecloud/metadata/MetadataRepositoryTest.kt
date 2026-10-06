package io.github.chenxiex.calibrecloud.metadata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import io.github.chenxiex.calibrecloud.tasks.local.LocalSnapshotTaskHandler
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real SQLite imports and app-state transactions; source fixtures are private independent copies. */
@RunWith(AndroidJUnit4::class)
class MetadataRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var directory: File
    private lateinit var state: ApplicationStateRepository
    private lateinit var repository: MetadataRepository
    private val libraryUuid = UUID.randomUUID()
    private val bookUuid = UUID.randomUUID()
    private val first = LibraryLocation.Local("fixture.documents", "first")
    private val second = LibraryLocation.OneDrive("fixture-account", "fixture-drive", "second")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "metadata-import-${UUID.randomUUID()}.db"
        directory = File(context.cacheDir, "metadata-import-${UUID.randomUUID()}").apply { mkdirs() }
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
    }

    @Test
    fun completeImportAndConfiguredSourceReadStateSurviveOfflineReopen() = runBlocking<Unit> {
        val selected = state.select(first)
        val source = fixture(boolValue = null)
        val bytes = source.readBytes()
        val identity = repository.importSnapshot(selected.token, source)!!
        val imported = repository.currentImport()!!
        assertEquals(identity, state.current()!!.identity)
        assertEquals(1, imported.metadata.books.size)
        assertEquals(ReadColumnStatus.NOT_CONFIGURED, imported.readColumnStatus)
        assertNull(imported.isRead(imported.metadata.books.single()))
        assertTrue(repository.selectReadColumn(imported, CustomColumnId(1, "#finished")))
        assertFalse(repository.selectReadColumn(imported, CustomColumnId(2, "#topic")))
        assertEquals(false, repository.currentImport()!!.isRead(imported.metadata.books.single()))
        assertArrayEquals(bytes, source.readBytes())
        val configured = repository.currentImport()!!
        database.close()
        reopen()
        assertEquals(configured, repository.currentImport())
    }

    @Test
    fun desktopSampleDynamicReadColumnUsesMissingFalseAndTrueAndSurvivesPrivateReopen() = runBlocking<Unit> {
        val source = desktopSampleCopy()
        val bytes = source.readBytes()
        val expectedStates = desktopSampleReadStates(source)
        assertTrue("Desktop sample must exercise an absent boolean row", expectedStates.values.any { it == null })
        assertTrue("Desktop sample must exercise explicit false", expectedStates.values.any { it == false })
        assertTrue("Desktop sample must exercise explicit true", expectedStates.values.any { it == true })
        repository.importSnapshot(state.select(first).token, source)
        val imported = repository.currentImport()!!
        assertEquals(expectedStates.keys, imported.metadata.books.map { it.sourceId }.toSet())
        assertEquals(ReadColumnStatus.NOT_CONFIGURED, imported.readColumnStatus)
        assertTrue(imported.metadata.books.all { imported.isRead(it) == null })
        val column = imported.metadata.columns.single { it.id.lookupName == "#read_status" }
        assertEquals("阅读状态", column.name)
        assertEquals("bool", column.datatype)
        assertTrue(column.supported)
        assertTrue(repository.selectReadColumn(imported, column.id))
        val configured = repository.currentImport()!!
        assertEquals(ReadColumnStatus.VALID, configured.readColumnStatus)
        configured.metadata.books.forEach { book ->
            assertEquals("Imported read state for source book ${book.sourceId}",
                expectedStates.getValue(book.sourceId) == true, configured.isRead(book))
        }
        assertArrayEquals("Import and choosing a column must not write the source snapshot", bytes, source.readBytes())
        assertTrue(source.delete())
        database.close()
        reopen()
        assertEquals("Private cache and configuration must survive with the source absent", configured, repository.currentImport())
        assertTrue(repository.selectReadColumn(repository.currentImport()!!, null))
        assertEquals(ReadColumnStatus.NOT_CONFIGURED, repository.currentImport()!!.readColumnStatus)
    }

    /** Programmatic mutations affect independent desktop-sample copies, not the user's Calibre library. */
    @Test
    fun desktopSampleCopyColumnRenameDeletionAndTypeChangeInvalidateConfiguredIdentity() = runBlocking<Unit> {
        val source = desktopSampleCopy()
        val originalBytes = source.readBytes()
        val token = state.select(first).token
        repository.importSnapshot(token, source)
        val initial = repository.currentImport()!!
        val column = initial.metadata.columns.single { it.id.lookupName == "#read_status" }
        assertTrue(repository.selectReadColumn(initial, column.id))
        val renamed = desktopSampleCopy()
        SQLiteDatabase.openDatabase(renamed.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE custom_columns SET label='renamed_read' WHERE id=?", arrayOf(column.id.sourceId))
        }
        repository.importSnapshot(token, renamed)
        val renamedImport = repository.currentImport()!!
        assertEquals(initial.identity, renamedImport.identity)
        assertEquals(ReadColumnStatus.INVALID, renamedImport.readColumnStatus)
        assertTrue(renamedImport.metadata.books.all { renamedImport.isRead(it) == null })
        val renamedColumn = renamedImport.metadata.columns.single { it.id.sourceId == column.id.sourceId }
        assertEquals("#renamed_read", renamedColumn.id.lookupName)
        assertFalse(repository.selectReadColumn(initial, renamedColumn.id))
        assertTrue(repository.selectReadColumn(renamedImport, renamedColumn.id))
        assertEquals(ReadColumnStatus.VALID, repository.currentImport()!!.readColumnStatus)
        val changedType = File(directory, "changed-type-${UUID.randomUUID()}.db").apply { writeBytes(renamed.readBytes()) }
        SQLiteDatabase.openDatabase(changedType.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE custom_columns SET datatype='composite' WHERE id=?", arrayOf(column.id.sourceId))
        }
        repository.importSnapshot(token, changedType)
        val changedImport = repository.currentImport()!!
        assertEquals(ReadColumnStatus.INVALID, changedImport.readColumnStatus)
        assertTrue(changedImport.metadata.books.all { changedImport.isRead(it) == null })
        assertFalse(repository.selectReadColumn(changedImport, renamedColumn.id))
        val deleted = File(directory, "deleted-column-${UUID.randomUUID()}.db").apply { writeBytes(renamed.readBytes()) }
        SQLiteDatabase.openDatabase(deleted.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM custom_columns WHERE id=?", arrayOf(column.id.sourceId))
        }
        repository.importSnapshot(token, deleted)
        val deletedImport = repository.currentImport()!!
        assertEquals(initial.identity, deletedImport.identity)
        assertEquals(ReadColumnStatus.INVALID, deletedImport.readColumnStatus)
        assertTrue(deletedImport.metadata.books.all { deletedImport.isRead(it) == null })
        assertFalse(repository.selectReadColumn(deletedImport, renamedColumn.id))
        assertTrue(repository.selectReadColumn(deletedImport, null))
        assertEquals(ReadColumnStatus.NOT_CONFIGURED, repository.currentImport()!!.readColumnStatus)
        assertArrayEquals("Mutation fixtures must leave the original sample copy intact", originalBytes, source.readBytes())
    }

    @Test
    fun corruptIncompatibleAndTransactionFailureRetainCompleteOldGeneration() = runBlocking<Unit> {
        val token = state.select(first).token
        repository.importSnapshot(token, fixture())
        val old = repository.currentImport()!!
        val corrupt = File(directory, "corrupt.db").apply { writeText("not SQLite") }
        assertTrue(runCatching { repository.importSnapshot(token, corrupt) }.isFailure)
        assertEquals(old, repository.currentImport())
        val incompatible = fixture()
        SQLiteDatabase.openDatabase(incompatible.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE books")
        }
        assertTrue(runCatching { repository.importSnapshot(token, incompatible) }.isFailure)
        assertEquals(old, repository.currentImport())
        database.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON metadata_books BEGIN SELECT RAISE(ABORT, 'fixture publication failure'); END")
        assertTrue(runCatching { repository.importSnapshot(token, fixture(boolValue = false)) }.isFailure)
        assertEquals(old, repository.currentImport())
        database.writableDatabase.execSQL("DROP TRIGGER fail_import")
        assertEquals(1, File(directory, "imports").listFiles()!!.size)
    }

    @Test
    fun switchBackRestoresCacheAndIncompatibleUuidReplacementGetsNewIdentity() = runBlocking<Unit> {
        val identity = repository.importSnapshot(state.select(first).token, fixture())!!
        val old = repository.currentImport()!!
        val other = repository.importSnapshot(state.select(second).token, fixture(library = UUID.randomUUID(), book = UUID.randomUUID()))!!
        assertNotEquals(identity.id, other.id)
        assertEquals(1L, repository.currentImport()!!.metadata.books.single().sourceId)
        state.select(first)
        assertEquals(old, repository.currentImport())
        val token = state.current()!!.token
        assertEquals(identity, repository.importSnapshot(token, fixture(boolValue = false)))
        assertNotEquals(old.generation, repository.currentImport()!!.generation)
        val replacedBook = repository.importSnapshot(token, fixture(book = UUID.randomUUID()))!!
        assertNotEquals(identity.id, replacedBook.id)
        val replacedLibrary = repository.importSnapshot(token, fixture(library = UUID.randomUUID()))!!
        assertNotEquals(identity.id, replacedLibrary.id)
        assertNotEquals(replacedBook.id, replacedLibrary.id)
    }

    @Test
    fun stalePublicationAndInterruptedGenerationNeverChangeNewSelection() = runBlocking<Unit> {
        val token = state.select(first).token
        repository.importSnapshot(token, fixture())
        val old = repository.currentImport()!!
        val orphan = File(directory, "imports/${UUID.randomUUID()}").apply { mkdirs() }
        File(orphan, "metadata.db").writeText("interrupted copy")
        var calls = 0
        val result = repository.importSnapshot(token, fixture()) {
            calls++
            if (calls == 2) state.select(second)
        }
        assertNull(result)
        assertEquals(second, state.current()!!.location)
        assertNull(repository.currentImport())
        assertFalse(orphan.exists())
        assertFalse(repository.selectReadColumn(old, CustomColumnId(1, "#finished")))
        state.select(first)
        assertEquals(old, repository.currentImport())
        assertNull(repository.importSnapshot(token, fixture()))
    }

    @Test
    fun publicationCompletesTaskAtomicallyBeforeLaterControl() = runBlocking<Unit> {
        val selected = state.select(first)
        val queue = DurableTaskQueue(database, Dispatchers.IO)
        val candidate = CandidateContext(selected.token, BackendKind.LOCAL, selected.token)
        val task = (queue.submit(TaskSubmission(TaskRequest.CandidateConfiguration(candidate,
            LocalSnapshotTaskHandler.OPERATION), TaskOrigin.MANUAL_SYNC)) as SubmissionResult.Created).taskId
        assertEquals(task, queue.claim(0, { emptySet() }, { true })!!.record.id)
        queue.update(task) { it.copy(record = it.record.copy(controls = TaskControls(true, true, false, false))) }
        assertNotNull(repository.importSnapshot(selected.token, fixture(), taskId = task.value))
        assertFalse("Published import must reject a later pause", queue.control(task, TaskControl.PAUSE))
        assertFalse("Published import must reject a later cancel", queue.control(task, TaskControl.CANCEL))
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
    }

    @Test
    fun renamedDeletedAndChangedTypeInvalidateConfigurationUntilExplicitChoice() = runBlocking<Unit> {
        val token = state.select(first).token
        repository.importSnapshot(token, fixture())
        val initial = repository.currentImport()!!
        assertTrue(repository.selectReadColumn(initial, CustomColumnId(1, "#finished")))
        repository.importSnapshot(token, fixture(boolLabel = "renamed"))
        val renamed = repository.currentImport()!!
        assertEquals(ReadColumnStatus.INVALID, renamed.readColumnStatus)
        assertNull(renamed.isRead(renamed.metadata.books.single()))
        assertFalse(repository.selectReadColumn(initial, CustomColumnId(1, "#renamed")))
        assertTrue(repository.selectReadColumn(renamed, CustomColumnId(1, "#renamed")))
        val changed = fixture(boolLabel = "renamed")
        SQLiteDatabase.openDatabase(changed.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE custom_columns SET datatype='composite' WHERE id=1")
        }
        repository.importSnapshot(token, changed)
        assertEquals(ReadColumnStatus.INVALID, repository.currentImport()!!.readColumnStatus)
        val deleted = fixture(boolLabel = "renamed")
        SQLiteDatabase.openDatabase(deleted.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM custom_columns WHERE id=1")
        }
        repository.importSnapshot(token, deleted)
        val current = repository.currentImport()!!
        assertEquals(ReadColumnStatus.INVALID, current.readColumnStatus)
        assertTrue(repository.selectReadColumn(current, null))
        assertEquals(ReadColumnStatus.NOT_CONFIGURED, repository.currentImport()!!.readColumnStatus)
    }

    private fun fixture(library: UUID = libraryUuid, book: UUID = bookUuid, boolLabel: String = "finished", boolValue: Boolean? = true) =
        CalibreFixture.create(File(directory, "source-${UUID.randomUUID()}.db"), library, book, boolLabel, boolValue)

    private fun desktopSampleCopy(): File = File(directory, "desktop-sample-${UUID.randomUUID()}.db").also { copy ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("calibre-sample/metadata.db").use { source ->
            copy.outputStream().use { source.copyTo(it) }
        }
    }

    private fun desktopSampleReadStates(source: File): Map<Long, Boolean?> =
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val columnId = db.rawQuery("SELECT id FROM custom_columns WHERE label='read_status' AND datatype='bool'", null).use {
                assertTrue(it.moveToFirst())
                it.getLong(0).also { _ -> assertFalse(it.moveToNext()) }
            }
            db.rawQuery("SELECT b.id,c.value FROM books b LEFT JOIN custom_column_$columnId c ON c.book=b.id ORDER BY b.id", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getLong(0), if (cursor.isNull(1)) null else cursor.getInt(1) == 1)
                }
            }
        }

    private fun reopen() {
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        repository = MetadataRepository(database, state, File(directory, "imports"), Dispatchers.IO)
    }
}
