package io.github.chenxiex.calibrecloud.ui

import android.graphics.Bitmap
import io.github.chenxiex.calibrecloud.library.BookSort
import io.github.chenxiex.calibrecloud.library.BookSortKey
import io.github.chenxiex.calibrecloud.library.Categorization
import io.github.chenxiex.calibrecloud.library.FolderKey
import io.github.chenxiex.calibrecloud.library.DownloadFilter
import io.github.chenxiex.calibrecloud.library.LibraryFilters
import io.github.chenxiex.calibrecloud.library.ReadFilter
import io.github.chenxiex.calibrecloud.library.SearchScope
import io.github.chenxiex.calibrecloud.library.LibraryImports
import io.github.chenxiex.calibrecloud.library.LibraryIndex
import io.github.chenxiex.calibrecloud.library.LibraryProblem
import io.github.chenxiex.calibrecloud.library.LibraryQueryService
import io.github.chenxiex.calibrecloud.metadata.ImportedBook
import io.github.chenxiex.calibrecloud.metadata.ImportedLibrary
import io.github.chenxiex.calibrecloud.metadata.ParsedLibrary
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.state.SearchHistoryStore
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraryId = LibraryId(UUID.randomUUID())
    private val identity = LibraryIdentity(libraryId, LibraryLocation.Local("a.documents", "tree"), UUID.randomUUID())
    private val token = UUID.randomUUID()
    private var selection: LibrarySelection? = LibrarySelection(token, identity.location, identity, BackendKind.LOCAL)
    private var imported: LibraryIndex? = null

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)
    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private class FakeImports(val current: () -> LibraryIndex?) : LibraryImports {
        override suspend fun currentRevision() = current()?.revision
        override suspend fun currentIndex() = current()
    }

    /** Cover cache is always empty; requests are recorded and never finish. */
    private class FakeCovers : LibraryCovers {
        val requested = mutableListOf<BookKey>()
        override suspend fun read(book: BookKey): Bitmap? = null
        override suspend fun request(book: BookKey, selectionToken: UUID): TaskId? {
            requested += book
            return TaskId(UUID.randomUUID())
        }
        override suspend fun awaitFinished(task: TaskId) = awaitCancellation()
        override suspend fun wake() {}
    }

    /** Book n is the nth added; tags alternate so folders hold different books. */
    private fun library(count: Int, columns: List<io.github.chenxiex.calibrecloud.metadata.ImportedColumn> = emptyList()) = LibraryIndex(
        ImportedLibrary(identity, UUID.randomUUID(), 1, ParsedLibrary(null, (1..count).map { n ->
            ImportedBook(n.toLong(), UUID(0, n.toLong()), "Book %03d".format(n), listOf("Author"), "2026-01-01T00:%02d:00+00:00".format(n),
                null, null, null, listOf(if (n % 2 == 0) "even" else "odd"), "", emptyList(), RelativeSourcePath("p$n"), true, emptyMap())
        }, columns), null, ReadColumnStatus.NOT_CONFIGURED))

    /** Newest first, like the database store. */
    private class FakeHistory : SearchHistoryStore {
        val saved = mutableMapOf<LibraryId, MutableList<String>>()
        override suspend fun list(libraryId: LibraryId) = saved[libraryId].orEmpty().toList()
        override suspend fun record(libraryId: LibraryId, query: String) {
            saved.getOrPut(libraryId) { mutableListOf() }.apply { remove(query); add(0, query) }
        }
        override suspend fun clear(libraryId: LibraryId) { saved.remove(libraryId) }
    }

    private val history = FakeHistory()

    private fun TestScope.model(covers: FakeCovers = FakeCovers()): LibraryViewModel {
        val service = LibraryQueryService(FakeImports { imported }, { emptyList() }, dispatcher)
        return LibraryViewModel({ selection }, service, covers, emptyFlow(), history).also {
            it.setVisible(true)
            advanceUntilIdle()
        }
    }

    private fun LibraryViewModel.titles() = (content as LibraryContent.Books).rows.map { it.title }

    @Test
    fun noLibraryAndNoImportAreDistinctStates() = runTest(dispatcher) {
        selection = null
        val model = model().also { it.onMeasured(6); advanceUntilIdle() }
        assertEquals(LibraryContent.Unconfigured, model.content)

        selection = LibrarySelection(token, identity.location, identity, BackendKind.ONEDRIVE)
        imported = null
        model.refresh()
        advanceUntilIdle()
        assertEquals(LibraryContent.NoMetadata, model.content)
        assertEquals(BackendKind.ONEDRIVE, model.backend)
    }

    @Test
    fun pageJumpsAreClampedToStableSlicesWithAPartialLastPage() = runTest(dispatcher) {
        imported = library(25)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        assertEquals((25 downTo 16).map { "Book %03d".format(it) }, model.titles())
        model.showPage(-1)
        advanceUntilIdle()
        assertEquals(0, (model.content as LibraryContent.Books).offset)
        model.showPage(9); advanceUntilIdle()
        assertEquals((5 downTo 1).map { "Book %03d".format(it) }, model.titles())
        assertEquals(20, (model.content as LibraryContent.Books).offset)
        assertEquals(25, (model.content as LibraryContent.Books).total)
        model.nextPage(); advanceUntilIdle()
        assertEquals(20, (model.content as LibraryContent.Books).offset)
        model.showPage(0); advanceUntilIdle()
        assertEquals(0, (model.content as LibraryContent.Books).offset)
    }

    @Test
    fun aNewSortKeyStartsInItsDefaultDirectionAndTheSameKeyReverses() = runTest(dispatcher) {
        imported = library(3)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        assertEquals(BookSort(BookSortKey.ADDED, false), model.sort)
        model.sortBy(BookSortKey.TITLE); advanceUntilIdle()
        assertEquals(BookSort(BookSortKey.TITLE, true), model.sort)
        assertEquals(listOf("Book 001", "Book 002", "Book 003"), model.titles())
        model.sortBy(BookSortKey.TITLE); advanceUntilIdle()
        assertEquals(listOf("Book 003", "Book 002", "Book 001"), model.titles())
        model.sortBy(BookSortKey.RATING)
        assertEquals(BookSort(BookSortKey.RATING, false), model.sort)
        model.sortBy(BookSortKey.ADDED)
        assertEquals(BookSort(BookSortKey.ADDED, false), model.sort)
    }

    @Test
    fun resizeKeepsTheFirstVisibleItemOnTheShownPage() = runTest(dispatcher) {
        imported = library(30)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        model.nextPage(); advanceUntilIdle()
        model.nextPage(); advanceUntilIdle()
        // Items 20..29 of the stable order are shown; with 8 per page the page holding item 20 starts at 16.
        model.onMeasured(8)
        advanceUntilIdle()
        val shown = model.content as LibraryContent.Books
        assertEquals(16, shown.offset)
        assertEquals(8, shown.rows.size)
        assertTrue("Book 010" in model.titles())
    }

    @Test
    fun folderBackRestoresTheRootPageAndCategorization() = runTest(dispatcher) {
        imported = library(30)
        val model = model().also { it.onMeasured(1); advanceUntilIdle() }
        model.categorize(Categorization.Tags); advanceUntilIdle()
        val folders = model.content as LibraryContent.Folders
        assertEquals(2, folders.total)
        assertEquals(listOf("even"), folders.rows.map { it.key.name })
        model.nextPage(); advanceUntilIdle()
        model.openFolder(FolderKey("odd")); advanceUntilIdle()
        assertEquals(15, (model.content as LibraryContent.Books).total)
        assertEquals(FolderKey("odd"), model.folder)

        assertTrue(model.closeFolder())
        advanceUntilIdle()
        assertEquals(null, model.folder)
        assertEquals(Categorization.Tags, model.categorization)
        assertEquals(listOf("odd"), (model.content as LibraryContent.Folders).rows.map { it.key.name })
        assertFalse(model.closeFolder())
    }

    @Test
    fun invalidCategoryColumnIsReportedInsteadOfShowingUncategorizedBooks() = runTest(dispatcher) {
        imported = library(3)
        val model = model().also { it.onMeasured(6); advanceUntilIdle() }
        model.categorize(Categorization.Column(CustomColumnId(9, "#gone")))
        advanceUntilIdle()
        assertEquals(LibraryContent.Problem(LibraryProblem.CATEGORY_COLUMN_INVALID), model.content)
    }

    @Test
    fun onlyCoversOfTheShownPageAreRequestedOncePerDisplayedPage() = runTest(dispatcher) {
        imported = library(25)
        val covers = FakeCovers()
        val model = model(covers).also { it.onMeasured(10); advanceUntilIdle() }
        assertEquals(10, covers.requested.size)
        assertEquals((16..25).map { it.toLong() }.toSet(), covers.requested.map { it.sourceId }.toSet())

        // A refresh of the same page reuses the outstanding request instead of enqueueing again.
        model.refresh(); advanceUntilIdle()
        assertEquals(10, covers.requested.size)

        model.nextPage(); advanceUntilIdle()
        assertEquals(20, covers.requested.size)
        assertEquals((6..15).map { it.toLong() }.toSet(), covers.requested.drop(10).map { it.sourceId }.toSet())
    }

    @Test
    fun folderRepresentativesRequestTheirCoversAndHiddenViewsRequestNothing() = runTest(dispatcher) {
        imported = library(10)
        val covers = FakeCovers()
        val model = model(covers).also { it.setVisible(false); it.categorize(Categorization.Tags); it.onMeasured(6); advanceUntilIdle() }
        assertTrue(covers.requested.isEmpty())
        model.setVisible(true); advanceUntilIdle()
        // Representatives are the newest book of each folder: 10 (even) and 9 (odd).
        assertEquals(setOf(10L, 9L), covers.requested.map { it.sourceId }.toSet())
    }

    @Test
    fun searchFromAFolderCoversTheLibraryAndBackRestoresTheFolderPage() = runTest(dispatcher) {
        imported = library(30)
        val model = model().also { it.onMeasured(4); advanceUntilIdle() }
        model.categorize(Categorization.Tags); advanceUntilIdle()
        model.openFolder(FolderKey("odd")); advanceUntilIdle()
        model.nextPage(); advanceUntilIdle()
        val folderPage = (model.content as LibraryContent.Books).offset

        model.openSearch(); advanceUntilIdle()
        assertEquals(null, model.search?.query)
        model.editSearch("book 02")
        // Typing alone runs nothing and saves nothing.
        assertEquals(null, model.search?.query)
        assertTrue(history.saved.isEmpty())
        model.submitSearch("book 02"); advanceUntilIdle()
        // Both keywords must match: Book 002 and 020..029, from both tag folders, flattened, from the first page.
        val found = model.content as LibraryContent.Books
        assertEquals(11, found.total)
        assertEquals(0, found.offset)
        assertEquals(listOf("book 02"), model.history)

        assertTrue(model.closeSearch()); advanceUntilIdle()
        assertEquals(FolderKey("odd"), model.folder)
        assertEquals(Categorization.Tags, model.categorization)
        assertEquals(folderPage, (model.content as LibraryContent.Books).offset)
        assertFalse(model.closeSearch())
    }

    @Test
    fun theSearchPageStartsFromTheLibrarySettingsAndKeepsItsChangesToItself() = runTest(dispatcher) {
        imported = library(6)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        model.showAs(LibraryViewMode.LIST)
        model.sortBy(BookSortKey.TITLE)
        model.updateFilters { it.copy(download = DownloadFilter.NOT_DOWNLOADED) }
        advanceUntilIdle()

        model.openSearch()
        assertEquals(LibraryViewMode.LIST, model.viewMode)
        assertEquals(BookSort(BookSortKey.TITLE, true), model.sort)
        assertEquals(DownloadFilter.NOT_DOWNLOADED, model.filters.download)
        model.submitSearch("book"); advanceUntilIdle()
        model.showAs(LibraryViewMode.GRID)
        model.sortBy(BookSortKey.RATING)
        model.updateFilters { LibraryFilters() }
        advanceUntilIdle()
        assertEquals(6, (model.content as LibraryContent.Books).total)

        model.closeSearch(); advanceUntilIdle()
        assertEquals(LibraryViewMode.LIST, model.viewMode)
        assertEquals(BookSort(BookSortKey.TITLE, true), model.sort)
        assertEquals(DownloadFilter.NOT_DOWNLOADED, model.filters.download)
        // A second visit starts again from the library's settings with an empty input.
        model.openSearch()
        assertEquals(LibraryViewMode.LIST, model.viewMode)
        assertEquals("", model.searchInput)
        assertEquals(SearchScope.All, model.search?.scope)
    }

    @Test
    fun aSeriesIndexSortIsNotCarriedIntoTheFlatSearch() = runTest(dispatcher) {
        imported = library(3)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        model.sortBy(BookSortKey.SERIES_INDEX)
        model.openSearch()
        assertEquals(BookSort.Default, model.sort)
    }

    @Test
    fun blankQueriesAreIgnoredAndEmptyingTheInputShowsTheHistoryAgain() = runTest(dispatcher) {
        imported = library(5)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        model.openSearch()
        model.submitSearch("   "); advanceUntilIdle()
        assertEquals(null, model.search?.query)
        assertTrue(model.history.isEmpty())

        model.submitSearch("Book 001"); advanceUntilIdle()
        model.submitSearch("Book 002"); advanceUntilIdle()
        assertEquals(listOf("Book 002", "Book 001"), model.history)
        model.editSearch("")
        assertEquals(null, model.search?.query)
        model.clearHistory(); advanceUntilIdle()
        assertTrue(model.history.isEmpty())
    }

    @Test
    fun aFieldLimitsTheExecutedQueryAndAnInvalidColumnFieldIsDropped() = runTest(dispatcher) {
        imported = library(4)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        model.openSearch()
        model.chooseScope(SearchScope.Tags)
        // Choosing a field does not run a search.
        assertEquals(null, model.search?.query)
        model.submitSearch("Book"); advanceUntilIdle()
        assertEquals(0, (model.content as LibraryContent.Books).total)
        model.submitSearch("even"); advanceUntilIdle()
        assertEquals(2, (model.content as LibraryContent.Books).total)

        model.chooseScope(SearchScope.Column(CustomColumnId(9, "#gone")))
        model.editSearch(""); advanceUntilIdle()
        assertEquals(SearchScope.All, model.search?.scope)
    }

    @Test
    fun filtersStartAgainFromTheFirstPageAndStayAcrossFolders() = runTest(dispatcher) {
        imported = library(30)
        val model = model().also { it.onMeasured(5); advanceUntilIdle() }
        model.nextPage(); advanceUntilIdle()
        model.updateFilters { it.copy(download = DownloadFilter.NOT_DOWNLOADED) }
        advanceUntilIdle()
        assertEquals(0, (model.content as LibraryContent.Books).offset)
        model.categorize(Categorization.Tags); advanceUntilIdle()
        model.openFolder(FolderKey("even")); advanceUntilIdle()
        assertEquals(DownloadFilter.NOT_DOWNLOADED, model.filters.download)
        model.updateFilters { it.copy(download = DownloadFilter.DOWNLOADED) }
        advanceUntilIdle()
        // Nothing is downloaded, so the folder is empty without falling back to unfiltered books.
        assertEquals(0, (model.content as LibraryContent.Books).total)
        model.closeFolder(); advanceUntilIdle()
        assertEquals(DownloadFilter.DOWNLOADED, model.filters.download)
    }

    @Test
    fun aReadFilterWithoutAValidColumnReportsItselfInsteadOfAnEmptyLibrary() = runTest(dispatcher) {
        imported = library(3)
        val model = model().also { it.onMeasured(10); advanceUntilIdle() }
        assertFalse(model.overview!!.readFilterAvailable)
        model.updateFilters { it.copy(read = ReadFilter.UNREAD) }
        advanceUntilIdle()
        assertEquals(LibraryContent.Problem(LibraryProblem.READ_FILTER_UNAVAILABLE), model.content)
    }

    private fun LibraryViewModel.nextPage() = showPage(pageStart(firstVisible, capacity) / capacity + 1)
}
