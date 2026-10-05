package io.github.chenxiex.calibrecloud.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus
import io.github.chenxiex.calibrecloud.storage.local.DirectoryGrant
import io.github.chenxiex.calibrecloud.storage.local.DirectoryPermissionAccess
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryAuthorization
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDirectoryAuthorizationViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun cleanUp() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** No source operations: this models only persisted grants and ordinary configuration. */
    private class Access : DirectoryPermissionAccess, LocalDirectoryConfiguration {
        var selected: String? = null
        var loads = 0
        val persisted = mutableListOf<Pair<String, Int>>()
        val saved = mutableListOf<String>()
        val released = mutableListOf<String>()
        private val grants = mutableSetOf<String>()

        override fun localLocation(treeUri: String) = LibraryLocation.Local("local", treeUri.substringAfterLast('/'))

        override fun persist(treeUri: String, resultFlags: Int) {
            persisted += treeUri to resultFlags
            grants += treeUri
        }

        override fun persistedGrant(treeUri: String) = DirectoryGrant(treeUri in grants, treeUri in grants)

        override fun release(treeUri: String) {
            released += treeUri
            grants -= treeUri
        }

        override fun load(): String? {
            loads++
            return selected
        }

        override fun save(treeUri: String): Boolean {
            selected = treeUri
            saved += treeUri
            return true
        }
    }

    private class Factory(private val access: Access, private val ioScheduler: TestCoroutineScheduler) : ViewModelProvider.Factory {
        var creations = 0

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            creations++
            return modelClass.cast(LocalDirectoryAuthorizationViewModel(
                LocalDirectoryAuthorization(access, access, StandardTestDispatcher(ioScheduler)),
            ))!!
        }
    }

    private fun newStore() = ViewModelStore().also { stores += it }

    @Test
    fun recreatedOwnerReusesViewModelAndReceivesPendingSelectionResult() = runTest {
        val ioScheduler = TestCoroutineScheduler()
        val access = Access()
        val factory = Factory(access, ioScheduler)
        val store = newStore()
        val original = ViewModelProvider(store, factory)[LocalDirectoryAuthorizationViewModel::class.java]
        val uri = "content://local/tree/library"
        original.select(uri, 0x43)
        testScheduler.runCurrent()
        assertTrue(original.busy)
        assertNull(access.selected)

        // A new provider represents the recreated Activity, with the retained ViewModelStore.
        val recreated = ViewModelProvider(store, factory)[LocalDirectoryAuthorizationViewModel::class.java]
        assertSame(original, recreated)
        assertEquals(1, factory.creations)
        recreated.refresh()
        ioScheduler.runCurrent()
        testScheduler.runCurrent()

        assertFalse(recreated.busy)
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, recreated.state.status)
        assertEquals(LibraryLocation.Local("local", "library"), recreated.state.location)
        assertEquals(uri, access.selected)
        assertEquals(listOf(uri to 0x43), access.persisted)
        assertEquals(1, access.loads)
    }

    @Test
    fun refreshSkipsPendingOperationAndRunsAgainAfterItCompletes() = runTest {
        val ioScheduler = TestCoroutineScheduler()
        val access = Access()
        val model = ViewModelProvider(newStore(), Factory(access, ioScheduler))[LocalDirectoryAuthorizationViewModel::class.java]
        model.refresh()
        assertTrue(model.busy)
        model.refresh()
        testScheduler.runCurrent()
        model.refresh()
        assertEquals(0, access.loads)

        ioScheduler.runCurrent()
        testScheduler.runCurrent()
        assertEquals(1, access.loads)
        assertFalse(model.busy)
        assertEquals(DirectoryAuthorizationStatus.UNSELECTED, model.state.status)

        model.refresh()
        testScheduler.runCurrent()
        ioScheduler.runCurrent()
        testScheduler.runCurrent()
        assertEquals(2, access.loads)
        assertFalse(model.busy)
    }

    @Test
    fun queuedSelectionsDeliverInOrderAndRemainBusyUntilAllResultsArrive() = runTest {
        val ioScheduler = TestCoroutineScheduler()
        val access = Access()
        val model = ViewModelProvider(newStore(), Factory(access, ioScheduler))[LocalDirectoryAuthorizationViewModel::class.java]
        val firstUri = "content://local/tree/first"
        val secondUri = "content://local/tree/second"
        model.select(firstUri, 0x41)
        model.select(secondUri, 0x43)
        assertTrue(model.busy)
        testScheduler.runCurrent()

        ioScheduler.runCurrent()
        assertEquals(listOf(firstUri), access.saved)
        testScheduler.runCurrent()
        assertEquals(LibraryLocation.Local("local", "first"), model.state.location)
        assertTrue(model.busy)
        assertEquals(listOf(firstUri), access.saved)

        ioScheduler.runCurrent()
        assertTrue(model.busy)
        assertEquals(listOf(firstUri, secondUri), access.saved)
        testScheduler.runCurrent()
        assertFalse(model.busy)
        assertEquals(LibraryLocation.Local("local", "second"), model.state.location)
        assertEquals(listOf(firstUri to 0x41, secondUri to 0x43), access.persisted)
        assertEquals(listOf(firstUri), access.released)
        assertEquals(secondUri, access.selected)
    }
}
