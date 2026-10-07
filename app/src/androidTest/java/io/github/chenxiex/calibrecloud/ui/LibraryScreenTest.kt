package io.github.chenxiex.calibrecloud.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chenxiex.calibrecloud.library.LibraryCopies
import io.github.chenxiex.calibrecloud.library.LibraryImports
import io.github.chenxiex.calibrecloud.library.LibraryIndex
import io.github.chenxiex.calibrecloud.library.LibraryQueryService
import io.github.chenxiex.calibrecloud.metadata.ImportedBook
import io.github.chenxiex.calibrecloud.metadata.ImportedColumn
import io.github.chenxiex.calibrecloud.metadata.ImportedColumnValue
import io.github.chenxiex.calibrecloud.metadata.ImportedFormat
import io.github.chenxiex.calibrecloud.metadata.ImportedLibrary
import io.github.chenxiex.calibrecloud.metadata.ParsedLibrary
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.state.SearchHistoryStore
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Production library page against fake local data: no source, network or task queue is touched. */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val libraryId = LibraryId(UUID.randomUUID())
    private val identity = LibraryIdentity(libraryId, LibraryLocation.Local("a.documents", "tree"), UUID.randomUUID())
    private val token = UUID.randomUUID()
    private val epub = BookFormat.parse("EPUB")
    private val readColumn = CustomColumnId(1, "#read")
    private val shelf = CustomColumnId(2, "#shelf")
    private var selection: LibrarySelection? = LibrarySelection(token, identity.location, identity, BackendKind.LOCAL)
    private var index: LibraryIndex? = null
    private var copies: List<DownloadedCopy> = emptyList()
    private val requestedCovers = mutableListOf<BookKey>()
    private var cover: Bitmap? = null
    private val finished = CompletableDeferred<Unit>()

    private fun book(n: Int, authors: List<String> = listOf("作者$n"), series: String? = null, size: Long? = 2048L,
        formats: Boolean = true) = ImportedBook(n.toLong(), UUID(0, n.toLong()), "书籍$n", authors,
        "2026-01-01T00:%02d:00+00:00".format(n), null, series, series?.let { n.toDouble() }, listOf(if (n % 2 == 0) "偶" else "奇"), "",
        if (formats) listOf(ImportedFormat(epub, size, RelativeSourcePath("p$n/b.epub"))) else emptyList(),
        RelativeSourcePath("p$n"), true, mapOf(1L to ImportedColumnValue.Bool(n == 1), 2L to ImportedColumnValue.Text(listOf("架"))))

    private fun library(books: List<ImportedBook>, read: ReadColumnStatus = ReadColumnStatus.VALID) {
        index = LibraryIndex(ImportedLibrary(identity, UUID.randomUUID(), 1_700_000_000_000, ParsedLibrary(null, books, listOf(
            ImportedColumn(readColumn, "已读", "bool", false, true),
            ImportedColumn(shelf, "书架", "enumeration", false, true),
        )), readColumn.takeIf { read != ReadColumnStatus.NOT_CONFIGURED }, read))
    }

    private val history = object : SearchHistoryStore {
        val saved = mutableListOf<String>()
        override suspend fun list(libraryId: LibraryId) = saved.toList()
        override suspend fun record(libraryId: LibraryId, query: String) { saved.remove(query); saved.add(0, query) }
        override suspend fun clear(libraryId: LibraryId) = saved.clear()
    }

    private fun model(): LibraryViewModel {
        val imports = object : LibraryImports {
            override suspend fun currentRevision() = index?.revision
            override suspend fun currentIndex() = index
        }
        val covers = object : LibraryCovers {
            override suspend fun read(book: BookKey) = cover
            override suspend fun request(book: BookKey, selectionToken: UUID): TaskId? {
                requestedCovers += book
                return TaskId(UUID.randomUUID())
            }
            override suspend fun awaitFinished(task: TaskId) = finished.await()
            override suspend fun wake() {}
        }
        return LibraryViewModel({ selection }, LibraryQueryService(imports, LibraryCopies { copies }, Dispatchers.Default), covers, emptyFlow(), history)
    }

    private fun show(model: LibraryViewModel, size: DpSize = DpSize(360.dp, 720.dp), openMore: (Int) -> Unit = {}) {
        compose.setContent { Box(Modifier.size(size)) { LibraryScreen(model, openMore) } }
    }

    private fun tagged(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun shown(prefix: String) = compose.onAllNodes(tagged(prefix)).fetchSemanticsNodes().size

    /** Pages the view menu forward until the entry is on the shown page. */
    private fun menuEntry(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        repeat(5) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return compose.onNodeWithTag(tag)
            // A menu that fits one page has no page row.
            val next = compose.onAllNodesWithTag("menu_next_page").fetchSemanticsNodes().singleOrNull()
            if (next == null || next.config.getOrNull(SemanticsProperties.Disabled) != null) return compose.onNodeWithTag(tag)
            compose.onNodeWithTag("menu_next_page").performClick()
        }
        return compose.onNodeWithTag(tag)
    }

    private fun direction(description: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun pageCapacityFollowsTheMeasuredSpaceAndNeverOverflowsIt() {
        library((1..40).map { book(it) })
        val size = mutableStateOf(DpSize(360.dp, 720.dp))
        val model = model()
        compose.setContent { Box(Modifier.size(size.value)) { LibraryScreen(model) {} } }
        awaitTag("book_40")
        val large = shown("book_")
        val area = compose.onNodeWithTag("library_content").getUnclippedBoundsInRoot()
        assertEquals(gridGeometry((area.right - area.left).value, (area.bottom - area.top).value, 96f, 4f).capacity, large)
        compose.onAllNodes(tagged("book_")).fetchSemanticsNodes().forEach {
            assertTrue(it.boundsInRoot.bottom <= compose.onNodeWithTag("library_content").fetchSemanticsNode().boundsInRoot.bottom + 1f)
        }

        compose.runOnIdle { size.value = DpSize(360.dp, 420.dp) }
        compose.waitUntil(10_000) { shown("book_") < large }
        assertTrue(shown("book_") in 1 until large)
        // The newest book (first in the default order) is still on the shown page.
        compose.onNodeWithTag("book_40").assertExists()
    }

    @Test
    fun pageRowDisablesItsEndsAndJumpsToTheFirstAndLastPage() {
        library((1..20).map { book(it) })
        show(model(), DpSize(360.dp, 560.dp))
        awaitTag("library_page_status")
        compose.onNodeWithTag("library_first_page").assertIsNotEnabled()
        compose.onNodeWithTag("library_previous_page").assertIsNotEnabled()
        compose.onNodeWithTag("library_next_page").assertIsEnabled()
        compose.onNodeWithTag("library_last_page").assertIsEnabled()
        val capacity = shown("book_")
        val pages = (20 + capacity - 1) / capacity
        assertTrue(pages >= 3)
        compose.onNodeWithTag("library_page_status").assertTextEquals("1 / $pages")
        compose.onNodeWithTag("library_last_page").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(20 - (pages - 1) * capacity, shown("book_"))
        compose.onNodeWithTag("library_page_status").assertTextEquals("$pages / $pages")
        compose.onNodeWithTag("library_next_page").assertIsNotEnabled()
        compose.onNodeWithTag("library_last_page").assertIsNotEnabled()
        compose.onNodeWithTag("library_previous_page").assertIsEnabled()
        compose.onNodeWithTag("library_first_page").performClick()
        awaitTag("book_20")
        compose.onNodeWithTag("library_page_status").assertTextEquals("1 / $pages")
    }

    @Test
    fun swipesTurnPagesBothWaysAndStopAtTheEnds() {
        library((1..20).map { book(it) })
        show(model(), DpSize(360.dp, 560.dp))
        awaitTag("library_page_status")
        val pages = compose.onNodeWithTag("library_page_status").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text.substringAfter("/ ").toInt()
        // Right or down on the first page does nothing.
        compose.onNodeWithTag("library_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("library_pager").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag("library_page_status").assertTextEquals("1 / $pages")
        // Right-to-left and bottom-to-top turn forward.
        compose.onNodeWithTag("library_pager").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("2 / $pages")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library_pager").performTouchInput { swipeUp() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("3 / $pages")).fetchSemanticsNodes().isNotEmpty() }
        // Left-to-right and top-to-bottom turn back.
        compose.onNodeWithTag("library_pager").performTouchInput { swipeRight() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("2 / $pages")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library_pager").performTouchInput { swipeDown() }
        awaitTag("book_20")
        compose.onNodeWithTag("library_page_status").assertTextEquals("1 / $pages")
    }

    @Test
    fun emptyLibraryAndInvalidCategoryExplainThemselves() {
        library(emptyList())
        val model = model()
        show(model)
        awaitText("书库中没有书籍。")
        library((1..3).map { book(it) })
        compose.runOnIdle {
            model.categorize(io.github.chenxiex.calibrecloud.library.Categorization.Column(CustomColumnId(9, "#gone")))
        }
        awaitText("请在“视图”菜单中重新选择分类")
        // The menu is still reachable to repair the choice.
        compose.onNodeWithTag("library_view_button").assertIsEnabled()
    }

    @Test
    fun noLibraryAndNoMetadataOfferTheirEntries() {
        selection = null
        val targets = mutableListOf<Int>()
        val model = model()
        show(model, openMore = { targets += it })
        awaitTag("library_open_settings")
        compose.onNodeWithTag("library_open_settings").performClick()
        assertEquals(listOf(MoreTarget.LOCAL_AUTHORIZATION), targets)

        selection = LibrarySelection(token, identity.location, identity, BackendKind.ONEDRIVE)
        index = null
        compose.runOnIdle { model.refresh() }
        awaitTag("library_sync")
        compose.onNodeWithTag("library_sync").performClick()
        compose.onNodeWithTag("library_downloaded_files").performClick()
        assertEquals(listOf(MoreTarget.LOCAL_AUTHORIZATION, MoreTarget.ONEDRIVE_TASKS, MoreTarget.DOWNLOAD_LIST), targets)
    }

    @Test
    fun gridAndListShowOnlyKnownFieldsAndTextualStates() {
        library(listOf(book(1), book(2, authors = emptyList(), size = null), book(3, formats = false)))
        copies = listOf(DownloadedCopy(CopyKey(BookKey(libraryId, 2, UUID(0, 2)), epub), CompleteCopyLocation(libraryId, UUID.randomUUID()),
            "书籍2", 9, FileVersion(BackendKind.LOCAL, "v"), SourceAvailability.AVAILABLE))
        val model = model()
        show(model)
        awaitTag("book_3")
        // Grid: cover boxes carry the title when the cover is missing; the read ribbon and download mark are text.
        assertEquals(1, compose.onAllNodes(hasText("已读")).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasContentDescription("已下载")).fetchSemanticsNodes().size)
        compose.onAllNodes(hasText("作者1")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

        compose.runOnIdle { model.showAs(LibraryViewMode.LIST) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("作者1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(android.text.format.Formatter.formatShortFileSize(compose.activity, 2048L)).assertExists()
        // Unknown author and size leave no placeholder; the read label stays visible with the size missing.
        compose.onAllNodes(hasText("未知", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onAllNodes(hasText("已读")).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test
    fun viewMenuDiffersBetweenRootAndFolderAndBackRestoresTheRoot() {
        library((1..6).map { book(it, series = if (it <= 3) "丛书甲" else null) })
        val model = model()
        show(model)
        awaitTag("library_view_button")
        compose.onNodeWithTag("library_view_button").performClick()
        compose.onNodeWithTag("library_view_button").assertIsSelected()
        compose.onNodeWithTag("menu_category_series").performClick()
        // Choosing keeps the menu open; the view button closes it.
        compose.onNodeWithTag("menu_category_series").assertIsSelected()
        menuEntry("menu_sort_folders").assert(direction("升序"))
        menuEntry("menu_sort_series_index").assertDoesNotExist()
        compose.onNodeWithTag("library_view_button").performClick()
        awaitTag("folder_丛书甲")
        compose.onNodeWithTag("library_menu").assertDoesNotExist()
        compose.onNodeWithTag("library_title").assertTextEquals("丛书")

        compose.onNodeWithTag("folder_丛书甲").performClick()
        awaitTag("book_3")
        compose.onNodeWithTag("library_title").assertTextEquals("丛书甲")
        compose.onNodeWithTag("library_view_button").performClick()
        compose.onNodeWithTag("menu_view_list").assertExists()
        compose.onNodeWithTag("menu_category_none").assertDoesNotExist()
        menuEntry("menu_sort_series_index").assert(direction("升序"))
        // System back closes the menu first, then the folder.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitTag("book_3")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitTag("folder_丛书甲")
        compose.onNodeWithTag("library_title").assertTextEquals("丛书")
        compose.onNodeWithTag("library_back").assertDoesNotExist()
    }

    @Test
    fun folderNamesReverseAndTheFallbackFolderStaysLast() {
        library((1..6).map { book(it, series = when (it) { 1, 2 -> "丛书甲"; 3 -> "丛书乙"; else -> null }) })
        val model = model()
        show(model)
        awaitTag("library_view_button")
        compose.runOnIdle { model.categorize(io.github.chenxiex.calibrecloud.library.Categorization.Series) }
        awaitTag("folder_丛书甲")
        val order = {
            compose.onAllNodes(tagged("folder_")).fetchSemanticsNodes()
                .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
                .map { it.config[SemanticsProperties.TestTag] }
        }
        assertEquals(listOf("folder_丛书乙", "folder_丛书甲", "folder_").sorted(), order().sorted())
        assertEquals("folder_", order().last())
        val ascending = order()
        compose.onNodeWithTag("library_view_button").performClick()
        menuEntry("menu_sort_folders").performClick()
        menuEntry("menu_sort_folders").assert(direction("降序"))
        compose.onNodeWithTag("library_view_button").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(tagged("folder_")).fetchSemanticsNodes().size == 3 && order() != ascending }
        assertEquals(ascending.dropLast(1).reversed() + "folder_", order())
    }

    @Test
    fun sortKeysShowTheirDirectionAndTheSameKeyReverses() {
        library((1..3).map { book(it) })
        val model = model()
        show(model)
        awaitTag("library_view_button")
        compose.onNodeWithTag("library_view_button").performClick()
        menuEntry("menu_sort_added").assert(direction("降序"))
        compose.onNodeWithTag("menu_sort_title").assertIsNotSelected().performClick()
        compose.onNodeWithTag("menu_sort_title").assert(direction("升序"))
        compose.onNodeWithTag("menu_sort_added").assertIsNotSelected()
        compose.onNodeWithTag("menu_sort_title").performClick()
        compose.onNodeWithTag("menu_sort_title").assert(direction("降序"))
        compose.onNodeWithTag("menu_sort_rating").performClick()
        compose.onNodeWithTag("menu_sort_rating").assert(direction("降序"))
        compose.onNodeWithTag("library_menu").assertExists()
    }

    @Test
    fun otherColumnsAreChosenFromThePopupBehindMore() {
        library((1..3).map { book(it) })
        val model = model()
        show(model)
        awaitTag("library_view_button")
        compose.onNodeWithTag("library_view_button").performClick()
        // Only the built-in categories and "更多" are listed in the menu itself.
        compose.onNodeWithTag("menu_category_column_2").assertDoesNotExist()
        compose.onNodeWithTag("menu_category_more").assertTextEquals("更多").performClick()
        compose.onNodeWithTag("category_columns").assertExists()
        // The boolean read column is not a category.
        compose.onNodeWithTag("menu_category_column_1").assertDoesNotExist()
        compose.onNodeWithTag("menu_category_column_2").assertTextEquals("书架").performClick()
        compose.onNodeWithTag("category_columns").assertDoesNotExist()
        compose.onNodeWithTag("library_menu").assertExists()
        compose.onNodeWithTag("menu_category_more").assertTextEquals("更多：书架").assertIsSelected()

        compose.onNodeWithTag("menu_category_more").performClick()
        compose.onNodeWithTag("category_columns_close").performClick()
        compose.onNodeWithTag("category_columns").assertDoesNotExist()
        compose.onNodeWithTag("library_view_button").performClick()
        awaitTag("folder_架")
        compose.onNodeWithTag("library_title").assertTextEquals("书架")
    }

    @Test
    fun longViewMenuPagesInsteadOfScrolling() {
        library((1..3).map { book(it) })
        show(model(), DpSize(360.dp, 400.dp))
        awaitTag("library_view_button")
        compose.onNodeWithTag("library_view_button").performClick()
        compose.onNodeWithTag("menu_first_page").assertIsNotEnabled()
        compose.onNodeWithTag("menu_previous_page").assertIsNotEnabled()
        compose.onNodeWithTag("menu_next_page").assertIsEnabled()
        compose.onNodeWithTag("menu_sort_rating").assertDoesNotExist()
        compose.onNodeWithTag("menu_last_page").performClick()
        compose.onNodeWithTag("menu_sort_rating").assertExists()
        compose.onNodeWithTag("menu_next_page").assertIsNotEnabled()
        // The menu pages by swipe through the same pager as the library.
        compose.onNodeWithTag("menu_pager").performTouchInput { swipeDown() }
        compose.onNodeWithTag("menu_sort_rating").assertDoesNotExist()
        compose.onNodeWithTag("menu_next_page").assertIsEnabled()
        // Every shown row lies inside the menu area.
        val menu = compose.onNodeWithTag("library_menu").fetchSemanticsNode().boundsInRoot
        compose.onAllNodes(tagged("menu_sort_")).fetchSemanticsNodes().forEach { assertTrue(it.boundsInRoot.bottom <= menu.bottom + 1f) }
    }

    @Test
    fun searchAndFilterNeedACompleteImport() {
        show(model())
        awaitTag("library_sync")
        compose.onNodeWithTag("library_search_button").assertIsNotEnabled()
        compose.onNodeWithTag("library_filter_button").assertIsNotEnabled()
        // No resident search box takes space above the content.
        compose.onNodeWithTag("search_input").assertDoesNotExist()
    }

    @Test
    fun searchPageCoversTheLibraryFromAFolderAndBackRestoresTheFolder() {
        library((1..30).map { book(it) })
        show(model())
        awaitTag("library_view_button")
        compose.onNodeWithTag("library_view_button").performClick()
        compose.onNodeWithTag("menu_category_tags").performClick()
        compose.onNodeWithTag("library_view_button").performClick()
        awaitTag("folder_奇")
        compose.onNodeWithTag("folder_奇").performClick()
        awaitTag("book_29")

        compose.onNodeWithTag("library_search_button").performClick()
        awaitTag("search_home")
        compose.onNodeWithTag("search_input").assertIsFocused()
        compose.onNodeWithTag("search_history_empty").assertExists()
        compose.onNodeWithTag("search_history_clear").assertIsNotEnabled()
        compose.onNodeWithTag("library_filter_button").assertIsEnabled()
        compose.onNodeWithTag("library_view_button").performClick()
        compose.onNodeWithTag("menu_view_list").assertExists()
        compose.onNodeWithTag("menu_category_none").assertDoesNotExist()
        menuEntry("menu_sort_series_index").assertDoesNotExist()
        compose.onNodeWithTag("library_view_button").performClick()

        // Typing alone runs nothing; the search key does.
        compose.onNodeWithTag("search_input").performTextInput("书籍4")
        compose.onNodeWithTag("search_home").assertExists()
        compose.onNodeWithTag("search_input").performImeAction()
        // Book 4 lies in the other tag folder.
        awaitTag("book_4")
        assertEquals(1, shown("book_"))

        compose.onNodeWithTag("search_input").performTextClearance()
        awaitTag("search_history_0")
        compose.onNodeWithTag("search_history_0").assertTextContains("书籍4")
        compose.onNodeWithTag("search_history_clear").assertIsEnabled()
        compose.onNodeWithTag("search_history_0").performClick()
        awaitTag("book_4")
        compose.onNodeWithTag("search_input").assertTextContains("书籍4")

        compose.onNodeWithTag("search_back").performClick()
        awaitTag("library_title")
        compose.onNodeWithTag("library_title").assertTextEquals("奇")
        compose.onNodeWithTag("book_29").assertExists()
        compose.onNodeWithTag("book_4").assertDoesNotExist()
    }

    @Test
    fun fieldBadgesLimitTheSearchAndTheClearButtonReturnsToTheHistory() {
        library((1..10).map { book(it) })
        show(model())
        awaitTag("library_search_button")
        compose.onNodeWithTag("library_search_button").performClick()
        awaitTag("search_scopes")
        compose.onNodeWithTag("search_scope_all").assertIsSelected()
        compose.onNodeWithTag("search_scope_column_2").assertTextContains("书架")
        compose.onNodeWithTag("search_scope_label").assertDoesNotExist()

        compose.onNodeWithTag("search_scope_authors").performClick()
        compose.onNodeWithTag("search_scope_authors").assertIsSelected()
        compose.onNodeWithTag("search_scope_all").assertIsNotSelected()
        compose.onNodeWithTag("search_scope_label").assertTextEquals("作者：")
        // Choosing a field runs nothing.
        compose.onNodeWithTag("search_home").assertExists()

        compose.onNodeWithTag("search_input").performTextInput("书籍3")
        compose.onNodeWithTag("search_input").performImeAction()
        awaitText("没有符合")
        compose.onNodeWithTag("search_input").performTextReplacement("作者3")
        compose.onNodeWithTag("search_input").performImeAction()
        awaitTag("book_3")
        assertEquals(1, shown("book_"))

        // The clear button empties the input and returns to the history, keeping the field and focus.
        compose.onNodeWithTag("search_clear").performClick()
        awaitTag("search_scope_all")
        compose.onNodeWithTag("search_clear").assertDoesNotExist()
        compose.onNodeWithTag("search_input").assertTextEquals("")
        compose.onNodeWithTag("search_input").assertIsFocused()
        compose.onNodeWithTag("search_scope_label").assertTextEquals("作者：")
        compose.onNodeWithTag("search_scope_all").performClick()
        compose.onNodeWithTag("search_scope_label").assertDoesNotExist()
    }

    @Test
    fun filterPanelTogglesInPlaceAndMarksTheButton() {
        library((1..6).map { book(it) })
        show(model())
        awaitTag("book_6")
        compose.onNodeWithTag("library_filter_button").performClick()
        compose.onNodeWithTag("library_filter_button").assertIsSelected()
        compose.onNodeWithTag("filter_read_unavailable").assertDoesNotExist()
        compose.onNodeWithTag("filter_format_EPUB").assertExists()
        // Read state is a single choice: another value replaces it and choosing it again clears it.
        compose.onNodeWithTag("filter_read_read").performClick()
        compose.onNodeWithTag("filter_read_unread").performClick()
        compose.onNodeWithTag("filter_read_unread").assertIsSelected()
        compose.onNodeWithTag("filter_read_read").assertIsNotSelected()
        compose.onNodeWithTag("filter_read_unread").performClick()
        compose.onNodeWithTag("filter_read_unread").assertIsNotSelected()
        compose.onNodeWithTag("library_filter_button").assert(hasContentDescription("筛选"))
        compose.onNodeWithTag("filter_read_read").performClick()
        // Choosing keeps the panel open; the filter button closes it.
        compose.onNodeWithTag("filter_read_read").assertIsSelected()
        compose.onNodeWithTag("library_filter_panel").assertExists()
        compose.onNodeWithTag("library_filter_button").assert(hasContentDescription("筛选（已启用）"))
        compose.onNodeWithTag("library_view_button").performClick()
        compose.onNodeWithTag("library_filter_panel").assertDoesNotExist()
        compose.onNodeWithTag("library_view_button").performClick()
        compose.onNodeWithTag("library_filter_button").performClick()
        compose.onNodeWithTag("filter_read_read").assertIsSelected()
        compose.onNodeWithTag("library_filter_button").performClick()
        awaitTag("book_1")
        assertEquals(1, shown("book_"))

        // Filters set on the search page stay there.
        compose.onNodeWithTag("library_search_button").performClick()
        awaitTag("search_home")
        compose.onNodeWithTag("library_filter_button").performClick()
        compose.onNodeWithTag("filter_read_read").performClick()
        compose.onNodeWithTag("library_filter_button").assert(hasContentDescription("筛选"))
        compose.onNodeWithTag("search_back").performClick()
        awaitTag("book_1")
        assertEquals(1, shown("book_"))
    }

    @Test
    fun readFilterWithoutAValidColumnIsUnavailableAndExplained() {
        library((1..6).map { book(it) }, read = ReadColumnStatus.INVALID)
        show(model())
        awaitTag("book_6")
        compose.onNodeWithTag("library_filter_button").performClick()
        compose.onNodeWithTag("filter_read_unavailable").assertExists()
        compose.onNodeWithTag("filter_read_read").assertIsNotEnabled()
        compose.onNodeWithTag("filter_read_unread").assertIsNotEnabled()
        compose.onNodeWithTag("filter_download_not_downloaded").assertIsEnabled()
        // Every book is shown without a read judgement instead of being treated as unread.
        compose.onNodeWithTag("library_filter_button").performClick()
        awaitTag("book_6")
        assertEquals(6, shown("book_"))
    }

    @Test
    fun bottomBarSwitchesBetweenLibraryAndMoreWithoutLosingTheLibraryPosition() {
        library((1..30).map { book(it) })
        val model = model()
        compose.setContent { MainScreen(model) { page, _ -> Text("更多页 $page") } }
        awaitTag("library_page_status")
        compose.onNodeWithTag("library_next_page").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_30").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("nav_library").assertIsSelected()
        compose.onNodeWithTag("nav_more").assertIsNotSelected().performClick()
        compose.onNodeWithText("更多页 0").assertExists()
        compose.onNodeWithTag("nav_more").assertIsSelected()
        compose.onNodeWithTag("library_page_status").assertDoesNotExist()
        compose.onNodeWithTag("nav_library").performClick()
        awaitTag("library_page_status")
        compose.onNodeWithTag("library_previous_page").assertIsEnabled()
    }

    @Test
    fun visibleCoversAreRequestedAndShownWhenTheTaskFinishes() {
        library((1..3).map { book(it) })
        val model = model()
        show(model)
        compose.waitUntil(10_000) { requestedCovers.size == 3 }
        // Titles stay as placeholders until an image exists.
        compose.onNodeWithText("书籍3").assertExists()
        assertEquals(0, model.coverImages.size)
        cover = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        finished.complete(Unit)
        compose.waitUntil(10_000) { model.coverImages.size == 3 }
        // Nothing was requested twice.
        assertEquals(3, requestedCovers.size)
    }

    @Test
    fun coverResultsOfAClearedImportAreNotFilledBack() {
        library((1..3).map { book(it) })
        val model = model()
        show(model)
        compose.waitUntil(10_000) { requestedCovers.size == 3 }
        // Clearing the metadata cache removes the library from view while the cover tasks are still outstanding.
        index = null
        compose.runOnIdle { model.refresh() }
        awaitTag("library_sync")
        cover = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        finished.complete(Unit)
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals(0, model.coverImages.size)
    }

    @Test
    fun cachedCoversNeedNoRequest() {
        library((1..3).map { book(it) })
        cover = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        val model = model()
        show(model)
        compose.waitUntil(10_000) { model.coverImages.size == 3 }
        assertTrue(requestedCovers.isEmpty())
    }
}
