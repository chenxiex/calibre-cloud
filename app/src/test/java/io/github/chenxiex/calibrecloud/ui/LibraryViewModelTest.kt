package io.github.chenxiex.calibrecloud.ui

import android.graphics.Bitmap
import io.github.chenxiex.calibrecloud.library.BookSort
import io.github.chenxiex.calibrecloud.library.BookSortKey
import io.github.chenxiex.calibrecloud.library.Categorization
import io.github.chenxiex.calibrecloud.library.FolderKey
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

    private fun TestScope.model(covers: FakeCovers = FakeCovers()): LibraryViewModel {
        val service = LibraryQueryService(FakeImports { imported }, { emptyList() }, dispatcher)
        return LibraryViewModel({ selection }, service, covers, emptyFlow()).also {
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

    private fun LibraryViewModel.nextPage() = showPage(pageStart(firstVisible, capacity) / capacity + 1)
}
