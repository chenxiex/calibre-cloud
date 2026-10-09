package io.github.chenxiex.calibrecloud.ui

import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
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
import io.github.chenxiex.calibrecloud.storage.cache.CleanupKind
import io.github.chenxiex.calibrecloud.storage.cache.CleanupPlan
import io.github.chenxiex.calibrecloud.library.Categorization
import io.github.chenxiex.calibrecloud.library.LibraryFilters
import androidx.compose.ui.test.longClick
import io.github.chenxiex.calibrecloud.tasks.api.FrozenSet
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.state.LastOpened
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        return LibraryViewModel({ selection }, LibraryQueryService(imports, LibraryCopies { copies }, Dispatchers.Default), covers, emptyFlow(),
            history, batch, syncState = { sync })
    }

    /** The unfinished library sync the model reports for the selection. */
    @Volatile private var sync: TaskState? = null

    /** Records batch submissions; removal plans cover one EPUB copy per book. */
    private val batch = object : LibraryBatch {
        override val readWriteAvailable = false
        val downloads = mutableListOf<CopyKey>()
        val previews = mutableListOf<Set<BookFormat>?>()
        val removed = mutableListOf<CleanupPlan>()
        override suspend fun download(key: CopyKey, selectionToken: UUID) = true.also { downloads += key }
        override suspend fun wake() {}
        override suspend fun previewRemoval(books: Set<BookKey>, formats: Set<BookFormat>?): CleanupPlan {
            previews += formats
            return CleanupPlan(CleanupKind.COPIES, token, setOf(libraryId), books.map { CopyKey(it, epub) }.toSet(), 4096, books, formats, libraryId)
        }
        override suspend fun remove(plan: CleanupPlan) = true.also { removed += plan }
    }

    /** Copies exist for [available] keys; downloads stay queued in [tasks] until a test finishes them. */
    private val opening = object : BookOpening {
        val available = mutableMapOf<CopyKey, DownloadedCopy>()
        val tasks = mutableMapOf<TaskId, MutableStateFlow<TaskState>>()
        val records = mutableMapOf<LibraryId, LastOpened>()
        val cancelled = mutableListOf<TaskId>()
        val active = MutableStateFlow<List<ActiveDownload>>(emptyList())
        override suspend fun selection() = this@LibraryScreenTest.selection
        override suspend fun locate(key: CopyKey) = available[key]?.let { LocatedCopy.Available(it) } ?: LocatedCopy.Missing
        override suspend fun download(key: CopyKey, selectionToken: UUID) =
            TaskId(UUID(2, key.book.sourceId)).also { tasks.getOrPut(it) { MutableStateFlow(TaskState.Queued) } }
        override suspend fun metadataAvailable() = index != null
        override fun observe(task: TaskId) = tasks.getValue(task)
        override suspend fun wake() {}
        override fun downloads() = active
        override suspend fun cancel(task: TaskId) { cancelled += task }
        override suspend fun lastOpened(libraryId: LibraryId) = records[libraryId]
        override suspend fun saveLastOpened(value: LastOpened) { records[value.key.book.libraryId] = value }
        override suspend fun cover(value: LastOpened): Bitmap? = null
    }

    private fun copyOf(key: CopyKey, title: String) = DownloadedCopy(key, CompleteCopyLocation(libraryId, UUID.randomUUID()), title, 9,
        FileVersion(BackendKind.LOCAL, "v"), SourceAvailability.AVAILABLE)

    private fun key(n: Int, format: BookFormat = epub) = CopyKey(BookKey(libraryId, n.toLong(), UUID(0, n.toLong())), format)

    private fun show(model: LibraryViewModel, size: DpSize = DpSize(360.dp, 720.dp), openMore: (Int) -> Unit = {}) {
        compose.setContent { Box(Modifier.size(size)) { LibraryScreen(model, openMore = openMore) } }
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
        assertEquals(listOf(MoreTarget.LOCATION), targets)

        selection = LibrarySelection(token, identity.location, identity, BackendKind.ONEDRIVE)
        index = null
        compose.runOnIdle { model.refresh() }
        awaitTag("library_sync")
        compose.onNodeWithTag("library_sync").performClick()
        compose.onNodeWithTag("library_downloaded_files").performClick()
        assertEquals(listOf(MoreTarget.LOCATION, MoreTarget.SYNC, MoreTarget.DOWNLOAD_LIST), targets)

        // The first sync of a newly added library is under way: no second sync is offered.
        sync = TaskState.Queued
        compose.runOnIdle { model.refresh() }
        awaitTag("library_sync_progress")
        compose.onNodeWithText("正在同步书库元数据，完成后自动显示书籍。").assertExists()
        compose.onAllNodesWithTag("library_sync").assertCountEquals(0)
        compose.onNodeWithTag("library_sync_progress").performClick()
        assertEquals(MoreTarget.SYNC, targets.last())
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
        compose.setContent { MainScreen(model, OpenViewModel(opening), { LaunchOutcome.STARTED }) { _, _, _ -> Text("更多页") } }
        awaitTag("library_page_status")
        compose.onNodeWithTag("library_next_page").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_30").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("nav_library").assertIsSelected()
        compose.onNodeWithTag("nav_more").assertIsNotSelected().performClick()
        compose.onNodeWithText("更多页").assertExists()
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

    @Test
    fun tappingABookOpensItsDefaultFormatAndABookWithoutFormatsSaysSo() {
        library(listOf(book(1), book(2, formats = false)))
        val opened = mutableListOf<Pair<CopyKey, String>>()
        val noFormat = mutableListOf<String>()
        val model = model()
        compose.setContent {
            Box(Modifier.size(DpSize(360.dp, 720.dp))) {
                LibraryScreen(model, onOpen = { key, title -> opened += key to title }, onNoFormat = { _, title -> noFormat += title }) {}
            }
        }
        awaitTag("book_1")
        compose.onNodeWithTag("book_1").performClick()
        compose.onNodeWithTag("book_2").performClick()
        compose.runOnIdle {
            assertEquals(listOf(key(1) to "书籍1"), opened)
            assertEquals(listOf("书籍2"), noFormat)
        }
    }

    @Test
    fun aStartedReaderBecomesTheLastOpenedEntryOfTheBottomBar() {
        library(listOf(book(1)))
        opening.available[key(1)] = copyOf(key(1), "书籍1")
        val launched = mutableListOf<CopyKey>()
        val open = OpenViewModel(opening)
        compose.setContent {
            MainScreen(model(), open, { launched += it.copy.key; LaunchOutcome.STARTED }) { _, _, _ -> Text("更多页") }
        }
        awaitTag("book_1")
        compose.onNodeWithTag("nav_last_opened").assertDoesNotExist()
        compose.onNodeWithTag("book_1").performClick()
        awaitTag("nav_last_opened")
        compose.onNodeWithTag("nav_last_opened").assert(hasContentDescription("上次打开：书籍1"))
        compose.onNode(hasTestTag("open_warning_1"), useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(key(1)), launched) }
        // The record reopens the same format from any page.
        compose.onNodeWithTag("nav_more").performClick()
        compose.onNodeWithTag("nav_last_opened").performClick()
        compose.waitUntil(10_000) { launched.size == 2 }
    }

    @Test
    fun aDownloadShowsAProgressMarkOnItsBookAndLeavingThePageRevokesTheOpen() {
        library(listOf(book(1)))
        val launched = mutableListOf<CopyKey>()
        val notices = mutableListOf<OpenNotice>()
        compose.setContent {
            MainScreen(model(), OpenViewModel(opening), { launched += it.copy.key; LaunchOutcome.STARTED }, { notices += it }) { _, _, _ ->
                Text("更多页")
            }
        }
        awaitTag("book_1")
        compose.onNodeWithTag("book_1").performClick()
        awaitTag("download_progress_1")
        compose.onNodeWithTag("download_progress_1").assert(hasContentDescription("取消下载《书籍1》"))
        // A queued or running download notifies nothing; a wait the user may have to resolve warns and notifies.
        compose.runOnIdle { assertTrue(notices.isEmpty()) }
        compose.runOnIdle { opening.tasks.values.single().value = TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))) }
        compose.waitUntil(10_000) { notices.size == 1 }
        compose.onNodeWithTag("download_progress_1").assert(hasContentDescription("《书籍1》的下载需要处理，原因见通知；点击取消下载"))
        compose.runOnIdle { assertEquals("等待网络", openStatusText(compose.activity, notices.single().status!!)) }
        compose.onNodeWithTag("nav_more").performClick()
        compose.onNodeWithTag("download_progress_1").assertDoesNotExist()
        opening.available[key(1)] = copyOf(key(1), "书籍1")
        compose.runOnIdle { opening.tasks.values.single().value = TaskState.Finished(TaskResult.Completed) }
        compose.waitForIdle()
        assertTrue(launched.isEmpty())
        compose.onNodeWithTag("nav_last_opened").assertDoesNotExist()
    }

    @Test
    fun theProgressMarkCancelsTheDownloadTask() {
        library(listOf(book(1), book(2)))
        copies = listOf(copyOf(key(2), "书籍2"))
        compose.setContent {
            MainScreen(model(), OpenViewModel(opening), { LaunchOutcome.STARTED }) { _, _, _ -> Text("更多页") }
        }
        awaitTag("book_1")
        compose.onNode(hasTestTag("download_mark") and hasAnyAncestor(hasTestTag("book_2")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("book_1").performClick()
        awaitTag("download_progress_1")
        compose.onNodeWithTag("download_progress_1").performClick()
        compose.onNodeWithTag("download_progress_1").assertDoesNotExist()
        compose.waitUntil(10_000) { opening.cancelled == listOf(TaskId(UUID(2, 1))) }
        compose.onNode(hasTestTag("open_warning_1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aDownloadWithoutAnOpenIsMarkedAndItsMarkCancelsIt() {
        library(listOf(book(1), book(2)))
        val task = TaskId(UUID(3, 2))
        opening.active.value = listOf(ActiveDownload(task, key(2).book, TaskState.Queued))
        // Built once, as the activity's view model is: a model built in composition would collect anew each time.
        val library = model()
        val open = OpenViewModel(opening)
        compose.setContent { MainScreen(library, open, { LaunchOutcome.STARTED }) { _, _, _ -> Text("更多页") } }
        awaitTag("download_progress_2")
        compose.onNodeWithTag("download_progress_2").assert(hasContentDescription("取消下载《书籍2》"))
        compose.onNodeWithTag("download_progress_1").assertDoesNotExist()
        compose.onNodeWithTag("download_progress_2").performClick()
        compose.waitUntil(10_000) { opening.cancelled == listOf(task) }
        compose.onNodeWithTag("download_progress_2").assertDoesNotExist()
    }

    @Test
    fun noReaderShowsAnErrorWithRetryAndKeepsNoRecord() {
        library(listOf(book(1)))
        opening.available[key(1)] = copyOf(key(1), "书籍1")
        var attempts = 0
        val notices = mutableListOf<OpenNotice>()
        compose.setContent {
            MainScreen(model(), OpenViewModel(opening), { attempts++; LaunchOutcome.NO_APP }, { notices += it }) { _, _, _ -> Text("更多页") }
        }
        awaitTag("book_1")
        compose.onNodeWithTag("book_1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("open_warning_1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open_warning_1", useUnmergedTree = true).assert(hasContentDescription("《书籍1》需要处理，原因见通知"))
        compose.runOnIdle {
            val status = notices.single().status!!
            assertEquals("无法打开《书籍1》", openNoticeTitle(compose.activity, status))
            assertTrue(openStatusText(compose.activity, status).contains("没有能打开 EPUB 的应用"))
        }
        compose.onNodeWithTag("nav_last_opened").assertDoesNotExist()
        // The warning is not a control of its own: tapping the book opens it again.
        compose.onNodeWithTag("book_1").performClick()
        compose.waitUntil(10_000) { attempts == 2 }
        compose.onNodeWithTag("nav_more").performClick()
        compose.onNodeWithTag("open_warning_1", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun longPress(tag: String) = compose.onNodeWithTag(tag).performTouchInput { longClick() }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun longPressStartsSelectionTapsToggleAcrossPagesAndDoneOrBackEndsIt() {
        library((1..20).map { book(it) })
        val opened = mutableListOf<CopyKey>()
        val model = model()
        compose.setContent { Box(Modifier.size(360.dp, 560.dp)) { LibraryScreen(model, onOpen = { key, _ -> opened += key }) {} } }
        awaitTag("book_20")
        longPress("book_20")
        awaitTag("selection_top_bar")
        // Search, filters and view cannot change the level while selecting.
        compose.onNodeWithTag("library_search_button").assertDoesNotExist()
        compose.onNodeWithTag("library_filter_button").assertDoesNotExist()
        compose.onNodeWithTag("library_view_button").assertDoesNotExist()
        compose.onNodeWithTag("book_20").assertIsSelected()
        compose.waitUntil(10_000) { model.selectedBooks == 1 }
        compose.onNodeWithTag("selection_count").assertTextEquals("已选 1 本")
        compose.onNodeWithTag("book_19").performClick()
        compose.waitUntil(10_000) { model.selectedBooks == 2 }
        compose.onNodeWithTag("book_19").assertIsSelected()
        compose.onNodeWithTag("library_next_page").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_20").fetchSemanticsNodes().isEmpty() }
        val other = compose.onAllNodes(tagged("book_")).fetchSemanticsNodes().first().config[SemanticsProperties.TestTag]
        compose.onNodeWithTag(other).assertIsNotSelected()
        compose.onNodeWithTag(other).performClick()
        compose.waitUntil(10_000) { model.selectedBooks == 3 }
        compose.onNodeWithTag(other).performClick()
        compose.waitUntil(10_000) { model.selectedBooks == 2 }
        compose.onNodeWithTag("library_previous_page").performClick()
        awaitTag("book_20")
        compose.onNodeWithTag("book_20").assertIsSelected()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithTag("selection_done").performClick()
        awaitTag("library_title")
        compose.onNodeWithTag("selection_top_bar").assertDoesNotExist()
        compose.onNodeWithTag("book_20").performClick()
        compose.waitUntil(10_000) { opened == listOf(key(20)) }
        longPress("book_19")
        awaitTag("selection_top_bar")
        back()
        awaitTag("library_title")
        compose.onNodeWithTag("selection_box").assertDoesNotExist()
    }

    @Test
    fun aChosenFolderCountsItsFilteredBooksOnceAndTheMarkFollowsTheirReadState() {
        // Book 1 is read; every book is on the same shelf and in an odd or even tag.
        library((1..4).map { book(it) })
        val model = model()
        show(model)
        awaitTag("book_4")
        compose.runOnIdle { model.categorize(Categorization.Column(shelf)) }
        awaitTag("folder_架")
        longPress("folder_架")
        compose.waitUntil(10_000) { model.selectedBooks == 4 }
        compose.onNodeWithTag("selection_more").performClick()
        awaitTag("selection_menu")
        // Mixed: only "mark read" is offered, disabled until source write-back exists.
        compose.onNodeWithTag("selection_mark_read").assertIsNotEnabled()
        compose.onNodeWithTag("selection_mark_unread").assertDoesNotExist()
        compose.onNode(hasAnyAncestor(hasTestTag("selection_mark_reason")) and hasText("尚未提供", substring = true)).assertExists()
        // A small popup at the top bar's right end; the page stays visible beside and under it.
        val menu = compose.onNodeWithTag("selection_menu").getUnclippedBoundsInRoot()
        assertEquals(240f, (menu.right - menu.left).value, 0.5f)
        assertEquals(356f, menu.right.value, 0.5f)
        assertEquals(56f, menu.top.value, 0.5f)
        compose.onNodeWithTag("folder_架").assertIsDisplayed()
        // A tap outside closes the menu without toggling what it lands on.
        compose.onNodeWithTag("folder_架").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("selection_menu").fetchSemanticsNodes().isEmpty() }
        assertEquals(4, model.selectedBooks)
        compose.onNodeWithTag("selection_more").performClick()
        awaitTag("selection_menu")
        back()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("selection_menu").fetchSemanticsNodes().isEmpty() }
        assertEquals(4, model.selectedBooks)
        back()
        awaitTag("library_title")
        compose.runOnIdle { model.categorize(Categorization.None) }
        awaitTag("book_1")
        longPress("book_1")
        compose.waitUntil(10_000) { model.selectedBooks == 1 }
        compose.onNodeWithTag("selection_more").performClick()
        // All read: "mark unread" replaces "mark read".
        awaitTag("selection_mark_unread")
        compose.onNodeWithTag("selection_mark_unread").assertIsNotEnabled()
        compose.onNodeWithTag("selection_mark_read").assertDoesNotExist()
    }

    @Test
    fun downloadingTheSelectionSubmitsEachBookAndNotifiesOnlyWhatWasNotDone() {
        library(listOf(book(1), book(2), book(3, formats = false)))
        copies = listOf(copyOf(key(2), "书籍2"))
        val model = model()
        val notices = mutableListOf<BatchNotice>()
        compose.setContent {
            MainScreen(model, OpenViewModel(opening), { LaunchOutcome.STARTED }, notifyBatch = { notices += it }) { _, _, _ -> Text("更多页") }
        }
        awaitTag("book_3")
        longPress("book_1")
        compose.onNodeWithTag("book_2").performClick()
        compose.onNodeWithTag("book_3").performClick()
        compose.waitUntil(10_000) { model.selectedBooks == 3 }
        compose.onNodeWithTag("selection_download").performClick()
        // Only what was not done reaches a system notification; nothing is shown in the page.
        compose.waitUntil(10_000) { notices.size == 1 }
        compose.runOnIdle {
            assertEquals("1 本没有可下载的格式，未下载", batchNoticeText(compose.activity, notices.single()))
            assertEquals(listOf(key(1)), batch.downloads)
            assertNull(model.notice)
        }
        compose.onNodeWithTag("selection_top_bar").assertDoesNotExist()
    }

    @Test
    fun removalShowsTheFrozenFormatScopeAndOnlyConfirmationRemoves() {
        library(listOf(book(1), book(2)))
        copies = listOf(copyOf(key(1), "书籍1"))
        val model = model()
        show(model)
        awaitTag("book_2")
        compose.runOnIdle { model.updateFilters { LibraryFilters(formats = setOf(epub)) } }
        longPress("book_1")
        compose.onNodeWithTag("book_2").performClick()
        compose.waitUntil(10_000) { model.selectedBooks == 2 }
        compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_remove").performClick()
        awaitTag("removal_dialog")
        compose.onNodeWithTag("removal_books", useUnmergedTree = true).assertTextEquals("涉及 2 本书")
        compose.onNodeWithTag("removal_formats", useUnmergedTree = true).assertTextEquals("格式：EPUB")
        compose.onNodeWithTag("removal_copies", useUnmergedTree = true).assertTextContains("将删除 2 个应用内副本", substring = true)
        compose.onNodeWithTag("removal_cancel").performClick()
        compose.onNodeWithTag("removal_dialog").assertDoesNotExist()
        compose.onNodeWithTag("selection_top_bar").assertExists()
        compose.runOnIdle { assertTrue(batch.removed.isEmpty()) }
        compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_remove").performClick()
        awaitTag("removal_confirm")
        compose.onNodeWithTag("removal_confirm").performClick()
        compose.waitUntil(10_000) { batch.removed.isNotEmpty() && model.selected == null }
        compose.runOnIdle {
            assertNull(model.notice)
            assertEquals(listOf<Set<BookFormat>?>(setOf(epub), setOf(epub)), batch.previews)
            assertEquals(setOf(epub), batch.removed.single().formats)
        }
        compose.onNodeWithTag("selection_top_bar").assertDoesNotExist()
    }
}
