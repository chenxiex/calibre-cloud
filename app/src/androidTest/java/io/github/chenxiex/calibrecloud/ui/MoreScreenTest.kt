package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Parts of the "更多" tab that need no source: menu search and paging, format order and static texts. */
@RunWith(AndroidJUnit4::class)
class MoreScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var metadataRoot: File
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "more-test-${UUID.randomUUID()}.db"
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        metadataRoot = File(context.cacheDir, "more-test-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        compose.runOnIdle { store.clear() }
        database.close()
        context.deleteDatabase(databaseName)
        metadataRoot.deleteRecursively()
    }

    private fun menu(): List<MenuEntry> = buildList {
        add(MenuEntry.Heading("元数据"))
        add(MenuEntry.Note("note", "上次同步失败：无法连接。原有效元数据保留。"))
        add(MenuEntry.Choice("more_sync", "立即同步元数据", supporting = "上次成功同步：昨天") {})
        add(MenuEntry.Choice("more_clear_metadata", "清除元数据缓存") {})
        add(MenuEntry.Rule)
        add(MenuEntry.Heading("存储与缓存"))
        add(MenuEntry.Choice("more_downloads", "已下载文件") {})
        add(MenuEntry.Choice("more_clear_others", "清除所有其它书库缓存") {})
        add(MenuEntry.Rule)
        add(MenuEntry.Heading("设置"))
        add(MenuEntry.Choice("more_formats", "格式优先级") {})
    }

    @Test
    fun searchFiltersOnlyMenuNamesAndKeepsTheirGroups() {
        var query by mutableStateOf("缓存")
        compose.setContent { Box(Modifier.height(600.dp)) { MenuSearchResults(menu(), query) } }
        compose.onNodeWithTag("more_clear_metadata").assertIsDisplayed()
        compose.onNodeWithTag("more_clear_others").assertIsDisplayed()
        compose.onNodeWithText("元数据").assertIsDisplayed()
        compose.onNodeWithText("存储与缓存").assertIsDisplayed()
        // Notes are not menu names, and groups without a match are left out.
        compose.onNodeWithTag("note").assertDoesNotExist()
        compose.onNodeWithTag("more_downloads").assertDoesNotExist()
        compose.onNodeWithText("设置").assertDoesNotExist()
        compose.runOnIdle { query = "其它 缓存" }
        compose.onNodeWithTag("more_clear_others").assertIsDisplayed()
        compose.onNodeWithTag("more_clear_metadata").assertDoesNotExist()
        compose.runOnIdle { query = "书名" }
        compose.onNodeWithTag("more_search_empty").assertTextEquals("没有名称匹配的菜单。")
    }

    @Test
    fun aShortMenuPagesWithoutLeavingAHeadingAtThePageEnd() {
        var page by mutableIntStateOf(0)
        compose.setContent { Box(Modifier.height(240.dp).width(360.dp)) { PagedEntries(menu(), page, { page = it }, Modifier, "more") } }
        compose.onNodeWithTag("more_page_status").assertTextEquals("1 / 3")
        compose.onNodeWithText("上次成功同步：昨天").assertIsDisplayed()
        // A one-line note is only as tall as its text, not a fixed three-line row.
        assertTrue(compose.onNodeWithTag("note").getUnclippedBoundsInRoot().height <= 32.dp)
        compose.onNodeWithTag("more_previous_page").assertIsNotEnabled()
        compose.onNodeWithTag("more_last_page").performClick()
        compose.onNodeWithTag("more_page_status").assertTextEquals("3 / 3")
        compose.onNodeWithTag("more_formats").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithTag("more_sync").assertDoesNotExist()
    }

    @Test
    fun movingAFormatSavesTheWholeOrderAndKeepsItOnScreen() = runBlocking<Unit> {
        val epub = BookFormat.parse("EPUB")
        val pdf = BookFormat.parse("PDF")
        val mobi = BookFormat.parse("MOBI")
        state.setFormatPriority(listOf(epub, mobi, pdf))
        val queue = DurableTaskQueue(database, Dispatchers.IO)
        lateinit var model: MetadataViewModel
        compose.runOnIdle {
            model = MetadataViewModel(state, MetadataRepository(database, state, metadataRoot, Dispatchers.IO), queue, { null }, {})
            store.put("metadata", model)
            model.restore()
        }
        compose.setContent { Box(Modifier.height(500.dp).width(360.dp)) { FormatPriorityPage(model) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("format_PDF").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("3. PDF").assertIsDisplayed()
        compose.onNodeWithTag("format_EPUB_up").assertIsNotEnabled()
        compose.onNodeWithTag("format_PDF_down").assertIsNotEnabled()
        compose.onNodeWithTag("format_PDF_up").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("format_PDF").fetchSemanticsNodes().isNotEmpty() &&
            runBlocking { state.formatPriority() } == listOf(epub, pdf, mobi) }
        compose.onNodeWithText("2. PDF").assertIsDisplayed()
        compose.onNodeWithTag("format_PDF_up").performClick()
        compose.waitUntil(10_000) { runBlocking { state.formatPriority() } == listOf(pdf, epub, mobi) }
        compose.onNodeWithText("1. PDF").assertIsDisplayed()
        assertEquals(listOf(pdf, epub, mobi), compose.runOnIdle { model.formats })
    }

    @Test
    fun licenseTextIsPagedWithoutScrolling() {
        compose.setContent { Box(Modifier.height(400.dp)) { LicenseTextPage(R.raw.license_gpl_3_0, "project_license") } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("project_license_page_status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("project_license_next_page").performClick()
        compose.onNodeWithTag("project_license_page_status").assertTextEquals("2 / ${pages("project_license")}")
        compose.onNodeWithTag("project_license_last_page").performClick()
        compose.onNodeWithTag("project_license_next_page").assertIsNotEnabled()
    }

    @Test
    fun noticesNameEveryComponentAndOpenItsLicense() {
        val opened = mutableListOf<String>()
        compose.setContent { Box(Modifier.height(600.dp)) { NoticesPage { opened += it } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notices_page_status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("AndroidX").assertIsDisplayed()
        compose.onNodeWithText("Copyright The Android Open Source Project").assertIsDisplayed()
        // The component list runs over several pages; the icons come last.
        compose.onNodeWithTag("notices_last_page").performClick()
        compose.onNodeWithText("Material Icons").assertIsDisplayed()
        compose.onNodeWithText("Copyright Google LLC").assertIsDisplayed()
        compose.onNodeWithTag("notice_license_Apache-2.0").performClick()
        assertEquals(listOf("Apache-2.0"), opened)
    }

    private fun pages(prefix: String): Int {
        val text = compose.onNodeWithTag("${prefix}_page_status").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")
        return text.substringAfter("/ ").trim().toInt()
    }
}
