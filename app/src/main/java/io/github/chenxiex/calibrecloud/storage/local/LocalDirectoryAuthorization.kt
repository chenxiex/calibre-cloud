package io.github.chenxiex.calibrecloud.storage.local

import io.github.chenxiex.calibrecloud.model.LibraryLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DirectoryGrant(val readable: Boolean, val writable: Boolean)

/** Only system grant metadata is inspected; no source document is opened or enumerated. */
interface DirectoryPermissionAccess {
    fun localLocation(treeUri: String): LibraryLocation.Local?
    fun persist(treeUri: String, resultFlags: Int)
    fun persistedGrant(treeUri: String): DirectoryGrant
    fun release(treeUri: String)
}

/** Private ordinary configuration, excluded from backup. save must report durable commit failure. */
interface LocalDirectoryConfiguration {
    fun load(): String?
    fun save(treeUri: String): Boolean
}

enum class DirectoryAuthorizationStatus { UNSELECTED, AUTHORIZED, READ_ONLY, REAUTHORIZATION_REQUIRED, UNSUPPORTED_PROVIDER }
enum class DirectorySelectionIssue { UNSUPPORTED_PROVIDER, PERSISTENCE_FAILED, CONFIGURATION_FAILED }

data class DirectoryAuthorizationState(
    val status: DirectoryAuthorizationStatus,
    val location: LibraryLocation.Local? = null,
    val selectionIssue: DirectorySelectionIssue? = null,
)

/**
 * Authorizes a location, never a validated library. All platform/configuration I/O runs on ioDispatcher.
 * Cancellation preserves the old selection; rejected replacements also leave it intact. A new grant
 * is verified and durably saved before the old one is released. Read/write flags do not prove safe
 * Calibre database commit capabilities. The OS grant is authoritative on every restore.
 */
class LocalDirectoryAuthorization(
    private val permissions: DirectoryPermissionAccess,
    private val configuration: LocalDirectoryConfiguration,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    suspend fun restore(): DirectoryAuthorizationState = withContext(ioDispatcher) {
        mutex.withLock { restoreLocked() }
    }

    /** null means picker cancellation. resultFlags are the actual returned Intent flags. */
    suspend fun select(treeUri: String?, resultFlags: Int): DirectoryAuthorizationState = withContext(ioDispatcher) {
        mutex.withLock {
            if (treeUri == null) return@withLock restoreLocked()
            val oldUri = configuration.load()
            val location = permissions.localLocation(treeUri)
                ?: return@withLock restoreLocked().copy(selectionIssue = DirectorySelectionIssue.UNSUPPORTED_PROVIDER)
            try {
                permissions.persist(treeUri, resultFlags)
                if (!permissions.persistedGrant(treeUri).readable) throw SecurityException()
            } catch (_: SecurityException) {
                if (treeUri != oldUri) releaseQuietly(treeUri)
                return@withLock restoreLocked().copy(selectionIssue = DirectorySelectionIssue.PERSISTENCE_FAILED)
            }
            if (!configuration.save(treeUri)) {
                if (treeUri != oldUri) releaseQuietly(treeUri)
                return@withLock restoreLocked().copy(selectionIssue = DirectorySelectionIssue.CONFIGURATION_FAILED)
            }
            if (oldUri != null && oldUri != treeUri) releaseQuietly(oldUri)
            stateFor(treeUri, location)
        }
    }

    private fun restoreLocked(): DirectoryAuthorizationState {
        val uri = configuration.load() ?: return DirectoryAuthorizationState(DirectoryAuthorizationStatus.UNSELECTED)
        val location = permissions.localLocation(uri)
            ?: return DirectoryAuthorizationState(DirectoryAuthorizationStatus.UNSUPPORTED_PROVIDER)
        return stateFor(uri, location)
    }

    private fun stateFor(uri: String, location: LibraryLocation.Local): DirectoryAuthorizationState {
        val grant = try {
            permissions.persistedGrant(uri)
        } catch (_: SecurityException) {
            DirectoryGrant(false, false)
        }
        val status = when {
            !grant.readable -> DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED
            !grant.writable -> DirectoryAuthorizationStatus.READ_ONLY
            else -> DirectoryAuthorizationStatus.AUTHORIZED
        }
        return DirectoryAuthorizationState(status, location)
    }

    private fun releaseQuietly(uri: String) {
        try {
            permissions.release(uri)
        } catch (_: SecurityException) {
            // A revoked grant already needs no release; never erase the committed selection.
        }
    }
}
