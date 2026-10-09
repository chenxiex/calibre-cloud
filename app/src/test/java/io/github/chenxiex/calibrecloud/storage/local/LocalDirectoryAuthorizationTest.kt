package io.github.chenxiex.calibrecloud.storage.local

import io.github.chenxiex.calibrecloud.model.LibraryLocation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class LocalDirectoryAuthorizationTest {
    private val uri = "content://local/tree/library"

    /** This fake has no source file access: only OS grant metadata and the tree root's name. */
    private class Access : DirectoryPermissionAccess {
        var persistFails = false
        var grantLookupFails = false
        var releaseFails = false
        var nameFails = false
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

        override fun displayName(treeUri: String): String? {
            checkThread?.invoke()
            if (nameFails) throw IllegalStateException("Provider unavailable")
            return "Calibre 书库"
        }
    }

    @Test
    fun cancellationTouchesNoGrant() = runTest {
        val access = Access()
        val authorization = LocalDirectoryAuthorization(access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectoryGrantResult.Cancelled, authorization.grant(null, 0))
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun actualPickerFlagsArePersistedAndTheGrantIsReturnedWithItsName() = runTest {
        val access = Access().apply { grants[uri] = DirectoryGrant(true, true) }
        val authorization = LocalDirectoryAuthorization(access, StandardTestDispatcher(testScheduler))
        val result = authorization.grant(uri, 0x41) as DirectoryGrantResult.Granted
        assertEquals(listOf(uri to 0x41), access.persisted)
        assertEquals(LibraryLocation.Local("local", "library"), result.location)
        assertEquals(uri, result.treeUri)
        assertEquals("Calibre 书库", result.displayName)
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, result.status)
        assertTrue(access.released.isEmpty())
    }

    @Test
    fun aGrantWithoutWriteIsReadOnlyAndAMissingNameIsNoFailure() = runTest {
        val access = Access().apply {
            grants[uri] = DirectoryGrant(true, false)
            nameFails = true
        }
        val authorization = LocalDirectoryAuthorization(access, StandardTestDispatcher(testScheduler))
        val result = authorization.grant(uri, 1) as DirectoryGrantResult.Granted
        assertEquals(DirectoryAuthorizationStatus.READ_ONLY, result.status)
        assertNull(result.displayName)
        assertEquals(DirectoryAuthorizationStatus.READ_ONLY, authorization.status(uri))
    }

    @Test
    fun anUnreadableOrFailedGrantIsRejectedAndReleasedUnlessALibraryKeepsIt() = runTest {
        listOf(false, true).forEach { kept ->
            listOf<Access.() -> Unit>({ }, { grants[uri] = DirectoryGrant(false, true) }, { persistFails = true }).forEach { setUp ->
                val access = Access().apply(setUp)
                val authorization = LocalDirectoryAuthorization(access, StandardTestDispatcher(testScheduler))
                val result = authorization.grant(uri, 3) { it == uri && kept }
                assertEquals(DirectoryGrantResult.Rejected(DirectorySelectionIssue.PERSISTENCE_FAILED), result)
                assertEquals(if (kept) emptyList() else listOf(uri), access.released)
            }
        }
    }

    @Test
    fun anUnsupportedProviderIsRejectedWithoutAskingForAGrant() = runTest {
        val access = Access().apply { unsupported += uri }
        val authorization = LocalDirectoryAuthorization(access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectoryGrantResult.Rejected(DirectorySelectionIssue.UNSUPPORTED_PROVIDER), authorization.grant(uri, 3))
        assertEquals(DirectoryAuthorizationStatus.UNSUPPORTED_PROVIDER, authorization.status(uri))
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun statusRechecksTheOsGrantEveryTime() = runTest {
        val access = Access().apply { grants[uri] = DirectoryGrant(true, true) }
        val authorization = LocalDirectoryAuthorization(access, StandardTestDispatcher(testScheduler))
        assertEquals(DirectoryAuthorizationStatus.UNSELECTED, authorization.status(null))
        assertEquals(DirectoryAuthorizationStatus.AUTHORIZED, authorization.status(uri))
        access.grants.clear()
        assertEquals(DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED, authorization.status(uri))
        access.grantLookupFails = true
        assertEquals(DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED, authorization.status(uri))
        assertTrue(access.events.isEmpty())
    }

    @Test
    fun releasingAnAlreadyRevokedGrantIsNoFailure() = runTest {
        val access = Access().apply { releaseFails = true }
        LocalDirectoryAuthorization(access, StandardTestDispatcher(testScheduler)).release(uri)
        assertEquals(listOf(uri), access.released)
    }

    @Test
    fun permissionAccessRunsOnInjectedIoDispatcher() = runTest {
        val access = Access().apply {
            grants[uri] = DirectoryGrant(true, true)
            checkThread = { assertEquals("directory-io", Thread.currentThread().name) }
        }
        Executors.newSingleThreadExecutor { task -> Thread(task, "directory-io") }.asCoroutineDispatcher().use { dispatcher ->
            val authorization = LocalDirectoryAuthorization(access, dispatcher)
            authorization.grant(uri, 3)
            authorization.status(uri)
            authorization.release(uri)
        }
    }
}
