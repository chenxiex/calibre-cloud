package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.auth.AuthStateStore
import io.github.chenxiex.calibrecloud.auth.OneDriveAuthorization
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.addLibrary
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.storage.local.DirectoryGrant
import io.github.chenxiex.calibrecloud.storage.local.DirectoryPermissionAccess
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryAuthorization
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorizations
import io.github.chenxiex.calibrecloud.tasks.local.LocalLibraryAuthorization
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveBrowseStore
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateService
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveLibraryAuthorization
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 书库 page and the add-library wizard over the real state database, queue and cache maintenance.
 * Only the system picker and the OS grant list are faked; nothing reaches a source.
 */
@RunWith(AndroidJUnit4::class)
class LibrariesPageTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var files: File
    private lateinit var model: LibraryListViewModel
    private lateinit var oneDrive: OneDriveAuthorizationViewModel
    private lateinit var directories: OneDriveLibraryViewModel
    private val permissions = Grants()
    private val store = ViewModelStore()
    private var syncs = 0
    private var picks = 0

    /** The OS grant list: picker results are granted read/write unless [failing]. */
    private class Grants : DirectoryPermissionAccess {
        val grants: MutableMap<String, DirectoryGrant> = Collections.synchronizedMap(mutableMapOf())
        val released: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun localLocation(treeUri: String) = LibraryLocation.Local("test.documents", treeUri.substringAfterLast('/').substringBefore('~'))
        override fun persist(treeUri: String, resultFlags: Int) { grants[treeUri] = DirectoryGrant(true, true) }
        override fun persistedGrant(treeUri: String) = grants[treeUri] ?: DirectoryGrant(false, false)
        override fun release(treeUri: String) { released += treeUri; grants.remove(treeUri) }
        override fun displayName(treeUri: String) = "书库 " + treeUri.substringAfterLast('/')
    }

    private class MemoryStore : AuthStateStore {
        private var value: String? = null
        override fun read() = value
        override fun write(serializedState: String) { value = serializedState }
        override fun clear() { value = null }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "libraries-test-${UUID.randomUUID()}.db"
        files = File(context.cacheDir, "libraries-test-${UUID.randomUUID()}").apply { mkdirs() }
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, PrivateBookFiles(files), Dispatchers.IO)
        val queue = DurableTaskQueue(database, Dispatchers.IO)
        val maintenance = CacheMaintenance(database, state, queue, files, Dispatchers.IO)
        val local = LocalDirectoryAuthorization(permissions, Dispatchers.IO)
        val localAccess = LocalLibraryAuthorization(local::status) { null }
        val oneDriveAccess = OneDriveLibraryAuthorization(state, { null }, { null })
        val authorizations = LibraryAuthorizations { if (it == BackendKind.LOCAL) localAccess else oneDriveAccess }
        val authorization = OneDriveAuthorization(context, null, MemoryStore())
        compose.runOnIdle {
            model = LibraryListViewModel(state, local, authorizations, maintenance, { null }, { syncs++; null }, {}, {})
            oneDrive = OneDriveAuthorizationViewModel(authorization)
            directories = OneDriveLibraryViewModel(OneDriveCandidateService(state, authorization, queue,
                TaskCoordinator(queue, emptyList()), OneDriveBrowseStore(File(files, "browse"))))
            store.put("libraries", model)
            store.put("onedrive", oneDrive)
            store.put("directories", directories)
        }
    }

    @After
    fun tearDown() {
        compose.runOnIdle { store.clear() }
        database.close()
        context.deleteDatabase(databaseName)
        files.deleteRecursively()
    }

    private val actions = MoreActions(selectDirectory = { picks++ }, login = {}, requestNotifications = {})

    /** The wizard as "更多" shows it: its top bar over the page; back on the first step reports [onCancel]. */
    @Composable
    private fun Wizard(onCancel: () -> Unit = {}, onDone: () -> Unit = {}) = Box(Modifier.height(640.dp)) {
        Column {
            AddLibraryTopBar(model, oneDrive, false, { model.back(onCancel) }, onDone)
            AddLibraryPage(model, oneDrive, directories, actions, false)
        }
    }

    private fun uri(name: String) = "content://test.documents/tree/$name"

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun addingALocalLibraryWalksTheThreeStepsAndSyncsIt() = runBlocking<Unit> {
        var done = 0
        compose.setContent { Wizard { done++ } }
        compose.onNodeWithTag("more_title").assertTextEquals("选择位置类型（1/3）")
        compose.onNodeWithContentDescription("取消").assertIsDisplayed()
        compose.onNodeWithTag("wizard_complete").assertDoesNotExist()
        compose.onNodeWithTag("wizard_type_local").performClick()
        // The picker opens with the type choice.
        compose.runOnIdle { assertEquals(1, picks) }
        compose.runOnIdle { model.picked(uri("first"), 3) }
        awaitTag("wizard_confirm")
        compose.onNodeWithTag("more_title").assertTextEquals("确认（3/3）")
        compose.onNodeWithTag("wizard_confirm_directory").assertTextEquals("目录：书库 first")
        // Nothing is listed or current before 完成.
        assertTrue(state.libraries().isEmpty())
        assertNull(state.current())

        // One step back releases the grant no library uses and offers the picker again.
        compose.onNodeWithContentDescription("上一步").performClick()
        awaitTag("wizard_local_choose")
        compose.onNodeWithTag("more_title").assertTextEquals("授权并选择书库目录（2/3）")
        compose.onNodeWithTag("wizard_complete").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(uri("first")), permissions.released) }
        compose.onNodeWithTag("wizard_local_choose").performClick()
        compose.runOnIdle { assertEquals(2, picks); model.picked(uri("first"), 3) }
        awaitTag("wizard_complete")
        compose.onNodeWithTag("wizard_complete").performClick()
        compose.waitUntil(10_000) { done == 1 }

        val location = LibraryLocation.Local("test.documents", "first")
        assertEquals(listOf(location), state.libraries().map { it.location })
        assertEquals("书库 first", state.libraries().single().displayName)
        assertEquals(uri("first"), state.accessKey(location))
        assertEquals(location, state.current()!!.location)
        assertNull(state.addition())
        assertEquals(1, syncs)
    }

    @Test
    fun backingOutOfTheWizardKeepsTheCurrentLibraryAndReleasesTheNewGrant() = runBlocking<Unit> {
        val current = state.addLibrary(LibraryLocation.Local("test.documents", "current"), uri("current"))
        permissions.grants[uri("current")] = DirectoryGrant(true, true)
        var cancelled = 0
        compose.setContent { Wizard({ cancelled++ }) }
        compose.onNodeWithTag("wizard_type_local").performClick()
        compose.runOnIdle { model.picked(uri("other"), 3) }
        awaitTag("wizard_confirm")
        // Back walks the steps; only on the first step does it leave the wizard.
        compose.onNodeWithTag("more_back").performClick()
        awaitTag("wizard_local")
        compose.onNodeWithTag("more_back").performClick()
        awaitTag("wizard_type")
        compose.runOnIdle { assertEquals(0, cancelled) }
        compose.onNodeWithTag("more_back").performClick()
        compose.waitUntil(10_000) { cancelled == 1 && model.addition == null && !model.busy }
        assertEquals(current, state.current())
        assertEquals(listOf(uri("other")), permissions.released.toList())
        assertEquals(1, state.libraries().size)
        assertEquals(0, syncs)
    }

    @Test
    fun theListSwitchesReauthorizesTheSameDirectoryOnlyAndDeletesTheCurrentLibrary() = runBlocking<Unit> {
        val first = LibraryLocation.Local("test.documents", "first")
        val second = LibraryLocation.Local("test.documents", "second")
        state.addLibrary(first, uri("first"), "第一书库")
        state.addLibrary(second, uri("second"), "第二书库")
        // The first library's grant was revoked; the second is current.
        permissions.grants[uri("second")] = DirectoryGrant(true, false)
        compose.runOnIdle { model.refresh() }
        compose.setContent { Box(Modifier.height(640.dp)) { LibrariesPage(model, oneDrive, actions) } }
        val firstTag = libraryTag(first)
        val secondTag = libraryTag(second)
        awaitTag(firstTag)
        // The check mark is part of the row's merged node.
        compose.onNodeWithTag("${secondTag}_current", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("${firstTag}_current", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("${firstTag}_status", useUnmergedTree = true).assertTextEquals("本地目录 · 目录授权已失效")
        compose.onNodeWithTag("${secondTag}_status", useUnmergedTree = true).assertTextEquals("本地目录 · 只读授权，不能写回已读状态")
        compose.onNodeWithTag("${secondTag}_reauthorize", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag(firstTag).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("${firstTag}_current", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("${secondTag}_current", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(first, state.current()!!.location)
        // The current row offers no switch, but it is not a disabled container around its own enabled actions.
        compose.onNodeWithTag(firstTag).assertHasNoClickAction().assertIsEnabled()
        compose.onNodeWithTag("${firstTag}_delete", useUnmergedTree = true).assertIsEnabled()

        // Re-authorizing accepts only the same directory.
        compose.onNodeWithTag("${firstTag}_reauthorize", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, picks); model.picked(uri("elsewhere"), 3) }
        awaitText("所选目录不是此书库的目录，授权未更改。")
        compose.runOnIdle { assertEquals(listOf(uri("elsewhere")), permissions.released.toList()) }
        assertEquals(uri("first"), state.accessKey(first))
        compose.onNodeWithTag("${firstTag}_reauthorize", useUnmergedTree = true).performClick()
        compose.runOnIdle { model.picked(uri("first~again"), 3) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("${firstTag}_reauthorize", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("${firstTag}_status", useUnmergedTree = true).assertTextEquals("本地目录 · 已授权")
        assertEquals(uri("first~again"), state.accessKey(first))

        // Deleting the current library confirms the range, then leaves no current library.
        compose.onNodeWithTag("${firstTag}_delete", useUnmergedTree = true).performClick()
        awaitTag("library_delete_dialog")
        compose.onNodeWithText("移除书库“第一书库”？").assertIsDisplayed()
        // The freed space covers the whole library, not only its copies.
        compose.onNodeWithText("应用空间：0 个书籍副本，以及索引", substring = true).assertIsDisplayed()
        compose.onNodeWithText("这是当前书库；移除后没有当前书库，需要另选或添加。").assertIsDisplayed()
        compose.onNodeWithTag("library_delete_dialog_confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(firstTag).fetchSemanticsNodes().isEmpty() && !model.busy }
        assertEquals(listOf(second), state.libraries().map { it.location })
        assertNull(state.current())
        assertTrue(uri("first~again") in permissions.released)
        assertTrue(uri("second") !in permissions.released)
    }
}
