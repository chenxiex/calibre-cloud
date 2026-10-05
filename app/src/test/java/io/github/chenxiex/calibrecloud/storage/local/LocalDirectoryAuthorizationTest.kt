package io.github.chenxiex.calibrecloud.storage.local

import io.github.chenxiex.calibrecloud.model.LibraryLocation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class LocalDirectoryAuthorizationTest {
    private val oldUri = "content://local/tree/old"
    private val newUri = "content://local/tree/new"

    /** This fake has no source file access: only configuration and OS grant metadata. */
    private class Access : DirectoryPermissionAccess, LocalDirectoryConfiguration {
        var selected: String? = null
        var commitSucceeds = true
        var persistFails = false
        var grantLookupFails = false
        var releaseFails = false
        var checkThread: (() -> Unit)? = null
        val grants = mutableMapOf<String, DirectoryGrant>()
        val unsupported = mutableSetOf<String>()
        val events = mutableListOf<String>()
        val persisted = mutableListOf<Pair<String, Int>>()
        val released = mutableListOf<String>()

        override fun localLocation(treeUri: String): LibraryLocation.Local? {
            checkThread?.invoke()
            return if (treeUri in unsupported) null else LibraryLocation.Local("local", treeUri.substringAfterLast('/'))
        }

        override fun persist(treeUri: String, resultFlags: Int) {
            checkThread?.invoke()
            persisted += treeUri to resultFlags
            events += "persist:$treeUri"
            if (persistFails) throw SecurityException("Permission denied")
        }

        override fun persistedGrant(treeUri: String): DirectoryGrant {
            checkThread?.invoke()
            if (grantLookupFails) throw SecurityException("Permission revoked")
            return grants[treeUri] ?: DirectoryGrant(false, false)
        }

        override fun release(treeUri: String) {
            checkThread?.invoke()
            released += treeUri
            events += "release:$treeUri"
            if (releaseFails) throw SecurityException("Already revoked")
            grants.remove(treeUri)
        }

        override fun load(): String? {
            checkThread?.invoke()
            return selected
        }

        override fun save(treeUri: String): Boolean {
            checkThread?.invoke()
            events += "save:$treeUri"
            if (commitSucceeds) selected = treeUri
            return commitSucceeds
        }
    }

    private fun authorizedOldSelection() = Access().apply {
        selected = oldUri
        grants[oldUri] = DirectoryGrant(true, true)
    }

    @Test
    fun cancellationPreservesOldSelectionAndNeverMutatesGrants() = runTest {
        val access = authorizedOldSelection()
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        val restored = authorization.restore()
        assertEquals(restored, authorization.select(null, 0))
        assertEquals(oldUri, access.selected)
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun actualPickerFlagsArePassedAndSuccessfulCommitPrecedesOldGrantRelease() = runTest {
        val access = authorizedOldSelection().apply { grants[newUri] = DirectoryGrant(true, true) }
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        val state = authorization.select(newUri, 0x41)
        assertEquals(listOf(newUri to 0x41), access.persisted)
        assertEquals(listOf("persist:$newUri", "save:$newUri", "release:$oldUri"), access.events)
        assertEquals(newUri, access.selected)
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, state.status)
        assertEquals(LibraryLocation.Local("local", "new"), state.location)
    }

    @Test
    fun readableGrantWithoutWritePermissionIsSavedAsReadOnly() = runTest {
        val access = Access().apply { grants[newUri] = DirectoryGrant(true, false) }
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectoryAuthorizationStatus.READ_ONLY, authorization.select(newUri, 1).status)
        assertEquals(newUri, access.selected)
        assertEquals(DirectoryAuthorizationStatus.READ_ONLY, authorization.restore().status)
    }

    @Test
    fun missingAndUnreadablePersistedGrantsRejectReplacementAndRetainOldGrant() = runTest {
        listOf(null, DirectoryGrant(false, true)).forEach { newGrant ->
            val access = authorizedOldSelection().apply { if (newGrant != null) grants[newUri] = newGrant }
            val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
            val state = authorization.select(newUri, 3)
            assertEquals(DirectorySelectionIssue.PERSISTENCE_FAILED, state.selectionIssue)
            assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, state.status)
            assertEquals(LibraryLocation.Local("local", "old"), state.location)
            assertEquals(oldUri, access.selected)
            assertEquals(listOf(newUri), access.released)
            assertEquals(DirectoryGrant(true, true), access.grants[oldUri])
            assertFalse(access.events.any { it.startsWith("save:") })
        }
    }

    @Test
    fun persistenceExceptionRejectsReplacementWithoutLosingOldSelection() = runTest {
        val access = authorizedOldSelection().apply { persistFails = true }
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectorySelectionIssue.PERSISTENCE_FAILED, authorization.select(newUri, 3).selectionIssue)
        assertEquals(oldUri, access.selected)
        assertEquals(listOf(newUri), access.released)
        assertEquals(DirectoryGrant(true, true), access.grants[oldUri])
    }

    @Test
    fun configurationFailureReleasesOnlyNewGrantAndRestoresOldSelection() = runTest {
        val access = authorizedOldSelection().apply {
            grants[newUri] = DirectoryGrant(true, true)
            commitSucceeds = false
        }
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        val state = authorization.select(newUri, 3)
        assertEquals(DirectorySelectionIssue.CONFIGURATION_FAILED, state.selectionIssue)
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, state.status)
        assertEquals(oldUri, access.selected)
        assertEquals(listOf(newUri), access.released)
        assertEquals(DirectoryGrant(true, true), access.grants[oldUri])
        assertNull(access.grants[newUri])
    }

    @Test
    fun retryingCurrentSelectionNeverReleasesItsGrantOnFailureOrSuccess() = runTest {
        val access = authorizedOldSelection().apply { commitSucceeds = false }
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectorySelectionIssue.CONFIGURATION_FAILED, authorization.select(oldUri, 3).selectionIssue)
        access.commitSucceeds = true
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, authorization.select(oldUri, 3).status)
        access.persistFails = true
        assertEquals(DirectorySelectionIssue.PERSISTENCE_FAILED, authorization.select(oldUri, 3).selectionIssue)
        assertTrue(access.released.isEmpty())
        assertEquals(DirectoryGrant(true, true), access.grants[oldUri])
    }

    @Test
    fun unsupportedReplacementDoesNotRequestPermissionOrReplaceSelection() = runTest {
        val access = authorizedOldSelection().apply { unsupported += newUri }
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        val state = authorization.select(newUri, 3)
        assertEquals(DirectorySelectionIssue.UNSUPPORTED_PROVIDER, state.selectionIssue)
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, state.status)
        assertEquals(oldUri, access.selected)
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun restoreRechecksRevokedGrantRatherThanTrustingSavedSelection() = runTest {
        val access = authorizedOldSelection()
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, authorization.restore().status)
        access.grants.clear()
        assertEquals(DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED, authorization.restore().status)
        access.grantLookupFails = true
        assertEquals(DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED, authorization.restore().status)
        assertEquals(oldUri, access.selected)
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun restoreDistinguishesNoSelectionAndUnsupportedSavedProvider() = runTest {
        val access = Access()
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectoryAuthorizationState(DirectoryAuthorizationStatus.UNSELECTED), authorization.restore())
        access.selected = oldUri
        access.unsupported += oldUri
        assertEquals(DirectoryAuthorizationState(DirectoryAuthorizationStatus.UNSUPPORTED_PROVIDER), authorization.restore())
        assertEquals(oldUri, access.selected)
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun revokedOldGrantDuringReleaseDoesNotUndoNewCommittedSelection() = runTest {
        val access = authorizedOldSelection().apply {
            grants[newUri] = DirectoryGrant(true, true)
            releaseFails = true
        }
        val authorization = LocalDirectoryAuthorization(access, access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, authorization.select(newUri, 3).status)
        assertEquals(newUri, access.selected)
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, authorization.restore().status)
    }

    @Test
    fun configurationAndPermissionAccessRunOnInjectedIoDispatcher() = runTest {
        val access = authorizedOldSelection().apply {
            grants[newUri] = DirectoryGrant(true, true)
            checkThread = { assertEquals("directory-io", Thread.currentThread().name) }
        }
        Executors.newSingleThreadExecutor { task -> Thread(task, "directory-io") }.asCoroutineDispatcher().use { dispatcher ->
            val authorization = LocalDirectoryAuthorization(access, access, dispatcher)
            authorization.restore()
            authorization.select(newUri, 3)
            authorization.select(null, 0)
        }
    }
}
