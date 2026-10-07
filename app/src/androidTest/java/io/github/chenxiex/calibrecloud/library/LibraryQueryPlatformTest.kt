package io.github.chenxiex.calibrecloud.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.CalibreFixture
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real imports of a Calibre 9.14.0 layout fixture plus the real manifest. Constructed fixtures show
 * parser and query capability only; they are not evidence from a desktop-maintained library.
 */
@RunWith(AndroidJUnit4::class)
class LibraryQueryPlatformTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var metadata: MetadataRepository
    private lateinit var maintenance: CacheMaintenance
    private lateinit var service: LibraryQueryService
    private val epub = BookFormat.parse("EPUB")
    private val pdf = BookFormat.parse("PDF")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "library-query-${UUID.randomUUID()}").apply { mkdirs() }
        databaseName = "library-query-${UUID.randomUUID()}.db"
        database = ApplicationStateDatabase(context, databaseName)
        val files = File(root, "files").apply { mkdirs() }
        state = ApplicationStateRepository(database, PrivateBookFiles(files), Dispatchers.IO)
        metadata = MetadataRepository(database, state, File(root, "metadata"), Dispatchers.IO)
        maintenance = CacheMaintenance(database, state, DurableTaskQueue(database, Dispatchers.IO), files, Dispatchers.IO)
        service = LibraryQueryService(MetadataLibraryImports(metadata), StateLibraryCopies(state), Dispatchers.Default)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    private suspend fun importFixture(extra: (SQLiteDatabase) -> Unit = {}): LibraryIdentity {
        val selected = state.select(LibraryLocation.Local("query.documents", "tree-${UUID.randomUUID()}"))
        val file = CalibreFixture.create(File(root, "source-${UUID.randomUUID()}.db"))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(extra)
        return requireNotNull(metadata.importSnapshot(selected.token, file))
    }

    @Test
    fun importedTextEnumerationAndMultiValueColumnsSearchCategorizeAndInvalidColumnsReport() = runBlocking<Unit> {
        importFixture { db ->
            db.execSQL("INSERT INTO books VALUES (2,?,'无标签书','2026-01-05 00:00:00+00:00','b/2',NULL,0)", arrayOf(UUID.randomUUID().toString()))
            db.execSQL("INSERT INTO data VALUES (2,'PDF',NULL,'正文')")
        }
        val tags = service.query(LibraryRequest(categorization = Categorization.Tags, pageSize = 10)) as LibraryQueryResult.Folders
        assertEquals(setOf("标签乙", "标签甲", null), tags.rows.map { it.key.name }.toSet())
        assertEquals(null, tags.rows.last().key.name)
        val subjects = service.query(LibraryRequest(categorization = Categorization.Column(CustomColumnId(3, "#subjects")), pageSize = 10)) as LibraryQueryResult.Folders
        assertEquals(setOf("甲", "乙", null), subjects.rows.map { it.key.name }.toSet())
        assertEquals(listOf("示例书"), (service.query(LibraryRequest(search = "收藏", searchScope = SearchScope.Column(CustomColumnId(4, "#shelf")), pageSize = 10))
            as LibraryQueryResult.Books).rows.map { it.title })
        assertEquals(listOf("示例书"), (service.query(LibraryRequest(search = "简介 说明", pageSize = 10)) as LibraryQueryResult.Books).rows.map { it.title })
        assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.CATEGORY_COLUMN_INVALID),
            service.query(LibraryRequest(categorization = Categorization.Column(CustomColumnId(5, "#formula")), pageSize = 10)))
        // Unconfigured read column: imported rows carry no read state.
        assertTrue((service.query(LibraryRequest(pageSize = 10)) as LibraryQueryResult.Books).rows.all { it.read == null })
    }

    @Test
    fun readColumnConfigurationRevisesViewsAndFiltersUseRealImportedValues() = runBlocking<Unit> {
        importFixture { db ->
            db.execSQL("INSERT INTO books VALUES (2,?,'未读书','2026-01-05 00:00:00+00:00','b/2',NULL,0)", arrayOf(UUID.randomUUID().toString()))
        }
        val before = (service.query(LibraryRequest(pageSize = 10)) as LibraryQueryResult.Books).revision
        val imported = metadata.currentImport()!!
        assertTrue(metadata.selectReadColumn(imported, CustomColumnId(1, "#finished")))
        assertEquals(LibraryQueryResult.Stale, service.query(LibraryRequest(pageSize = 10, expected = before)))
        val read = service.query(LibraryRequest(filters = LibraryFilters(read = ReadFilter.READ), pageSize = 10)) as LibraryQueryResult.Books
        assertEquals(listOf("示例书"), read.rows.map { it.title })
        assertEquals(listOf(true), read.rows.map { it.read })
        val unread = service.query(LibraryRequest(filters = LibraryFilters(read = ReadFilter.UNREAD), pageSize = 10)) as LibraryQueryResult.Books
        assertEquals(listOf("未读书"), unread.rows.map { it.title })
    }

    @Test
    fun manifestDrivesDownloadedStateAndClearedMetadataLeavesNoCompleteQuery() = runBlocking<Unit> {
        val identity = importFixture()
        val book = BookKey(identity.id, 1, metadata.currentImport()!!.metadata.books.single().sourceUuid)
        val copy = DownloadedCopy(CopyKey(book, pdf), CompleteCopyLocation(identity.id, UUID.randomUUID()), "示例书", 5,
            FileVersion(BackendKind.LOCAL, "v"), SourceAvailability.AVAILABLE)
        File(root, "files/books/${identity.id.value}/${copy.location.fileGeneration}.book").apply { parentFile!!.mkdirs(); writeText("12345") }
        assertTrue(state.publishComplete(copy))
        val epubOnly = LibraryRequest(filters = LibraryFilters(download = DownloadFilter.DOWNLOADED, formats = setOf(epub)), pageSize = 10)
        assertTrue((service.query(epubOnly) as LibraryQueryResult.Books).rows.isEmpty())
        val any = (service.query(LibraryRequest(filters = LibraryFilters(download = DownloadFilter.DOWNLOADED), pageSize = 10)) as LibraryQueryResult.Books).rows.single()
        assertEquals(DefaultFormat(pdf, true, 5, false), any.defaultFormat)
        val plan = maintenance.previewMetadata()!!
        assertTrue(maintenance.execute(plan))
        assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.NO_METADATA), service.query(LibraryRequest(pageSize = 10)))
        assertNotNull("The manifest is retained but does not stand in for a complete library", state.find(copy.key))
    }
}
