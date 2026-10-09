package io.github.chenxiex.calibrecloud.storage.local

import io.github.chenxiex.calibrecloud.model.LibraryLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DirectoryGrant(val readable: Boolean, val writable: Boolean)

/** Only system grant metadata and the tree root's display name are inspected; no source document is opened or enumerated. */
interface DirectoryPermissionAccess {
    fun localLocation(treeUri: String): LibraryLocation.Local?
    fun persist(treeUri: String, resultFlags: Int)
    fun persistedGrant(treeUri: String): DirectoryGrant
    fun release(treeUri: String)
    /** The chosen directory's name as the provider shows it; null when the provider gives none. */
    fun displayName(treeUri: String): String?
}

enum class DirectoryAuthorizationStatus { UNSELECTED, AUTHORIZED, READ_ONLY, REAUTHORIZATION_REQUIRED, UNSUPPORTED_PROVIDER }
enum class DirectorySelectionIssue { UNSUPPORTED_PROVIDER, PERSISTENCE_FAILED }

/** A picker result: the persisted grant [treeUri] for [location], or why none was kept. */
sealed interface DirectoryGrantResult {
    data class Granted(val treeUri: String, val location: LibraryLocation.Local, val displayName: String?,
        val status: DirectoryAuthorizationStatus) : DirectoryGrantResult
    data object Cancelled : DirectoryGrantResult
    data class Rejected(val issue: DirectorySelectionIssue) : DirectoryGrantResult
}

/**
 * Persisted directory grants of local libraries, one per listed library; never a validated library.
 * Platform I/O runs on ioDispatcher and is serialized. A rejected picker result keeps no grant. The
 * caller records a granted URI as the library's access key and releases a grant once no library uses
 * it. Read/write flags do not prove safe Calibre database commit capabilities. The OS grant is
 * authoritative on every status check.
 */
class LocalDirectoryAuthorization(
    private val permissions: DirectoryPermissionAccess,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    /** null [treeUri] means picker cancellation. resultFlags are the actual returned Intent flags. */
    suspend fun grant(treeUri: String?, resultFlags: Int, keep: suspend (String) -> Boolean = { false }): DirectoryGrantResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                if (treeUri == null) return@withLock DirectoryGrantResult.Cancelled
                val location = permissions.localLocation(treeUri)
                    ?: return@withLock DirectoryGrantResult.Rejected(DirectorySelectionIssue.UNSUPPORTED_PROVIDER)
                try {
                    permissions.persist(treeUri, resultFlags)
                    if (!permissions.persistedGrant(treeUri).readable) throw SecurityException()
                } catch (_: SecurityException) {
                    // A grant another library still uses is never released here.
                    if (!keep(treeUri)) releaseQuietly(treeUri)
                    return@withLock DirectoryGrantResult.Rejected(DirectorySelectionIssue.PERSISTENCE_FAILED)
                }
                val name = try { permissions.displayName(treeUri) } catch (_: Exception) { null }
                DirectoryGrantResult.Granted(treeUri, location, name, statusLocked(treeUri))
            }
        }

    /** The grant state of a library's access key; UNSELECTED when it has none. */
    suspend fun status(treeUri: String?): DirectoryAuthorizationStatus = withContext(ioDispatcher) {
        mutex.withLock {
            when {
                treeUri == null -> DirectoryAuthorizationStatus.UNSELECTED
                permissions.localLocation(treeUri) == null -> DirectoryAuthorizationStatus.UNSUPPORTED_PROVIDER
                else -> statusLocked(treeUri)
            }
        }
    }

    suspend fun release(treeUri: String) = withContext(ioDispatcher) { mutex.withLock { releaseQuietly(treeUri) } }

    private fun statusLocked(uri: String): DirectoryAuthorizationStatus {
        val grant = try {
            permissions.persistedGrant(uri)
        } catch (_: SecurityException) {
            DirectoryGrant(false, false)
        }
        return when {
            !grant.readable -> DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED
            !grant.writable -> DirectoryAuthorizationStatus.READ_ONLY
            else -> DirectoryAuthorizationStatus.AUTHORIZED
        }
    }

    private fun releaseQuietly(uri: String) {
        try {
            permissions.release(uri)
        } catch (_: SecurityException) {
            // A revoked grant already needs no release.
        }
    }
}
