package io.github.chenxiex.calibrecloud.state

import android.database.sqlite.SQLiteException
import io.github.chenxiex.calibrecloud.storage.local.DirectoryPermissionAccess
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryConfiguration

/** The local grant reference and current candidate are committed together, before releasing an old grant. */
class DatabaseLocalDirectoryConfiguration(
    private val state: ApplicationStateRepository,
    private val permissions: DirectoryPermissionAccess,
    private val legacy: LocalDirectoryConfiguration,
) : LocalDirectoryConfiguration {
    override fun load(): String? {
        state.localTreeUri()?.let { return it }
        val uri = legacy.load() ?: return null
        val location = permissions.localLocation(uri) ?: return uri
        state.importLocalAuthorization(uri, location)
        return state.localTreeUri()
    }

    override fun save(treeUri: String): Boolean {
        val location = permissions.localLocation(treeUri) ?: return false
        return try {
            state.saveLocalSelection(treeUri, location)
            true
        } catch (_: SQLiteException) {
            false
        }
    }
}
