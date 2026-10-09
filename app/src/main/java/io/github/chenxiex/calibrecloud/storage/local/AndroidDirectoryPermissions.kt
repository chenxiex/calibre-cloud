package io.github.chenxiex.calibrecloud.storage.local

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.net.toUri
import android.provider.DocumentsContract
import io.github.chenxiex.calibrecloud.model.LibraryLocation

/** Known AOSP local/SD storage provider only; unknown OEM and cloud providers require evidence. */
class AndroidDirectoryPermissions(private val context: Context) : DirectoryPermissionAccess {
    private val resolver = context.contentResolver

    override fun localLocation(treeUri: String): LibraryLocation.Local? {
        val uri = treeUri.toUri()
        if (uri.scheme != "content" || uri.authority != LOCAL_AUTHORITY || uri.query != null || uri.fragment != null) return null
        if (!DocumentsContract.isTreeUri(uri) || uri.pathSegments.size != 2) return null
        val provider = context.packageManager.resolveContentProvider(LOCAL_AUTHORITY, 0) ?: return null
        if (provider.packageName != "com.android.externalstorage" ||
            provider.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        ) return null
        return try {
            LibraryLocation.Local(LOCAL_AUTHORITY, DocumentsContract.getTreeDocumentId(uri))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun persist(treeUri: String, resultFlags: Int) {
        if (resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0 ||
            resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) throw SecurityException()
        val actualFlags = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            resolver.takePersistableUriPermission(treeUri.toUri(), actualFlags)
        } catch (_: IllegalArgumentException) {
            throw SecurityException()
        }
    }

    override fun persistedGrant(treeUri: String): DirectoryGrant {
        val uri = treeUri.toUri()
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        return DirectoryGrant(grant?.isReadPermission == true, grant?.isWritePermission == true)
    }

    override fun release(treeUri: String) {
        val uri = treeUri.toUri()
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        resolver.releasePersistableUriPermission(uri, flags)
    }

    override fun displayName(treeUri: String): String? {
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0).takeIf { name -> name.isNotBlank() } else null
        }
    }

    companion object {
        const val LOCAL_AUTHORITY = "com.android.externalstorage.documents"
    }
}

/** Uses the process-owned database/configuration and authorization coordinator. */
fun createLocalDirectoryAuthorization(context: Context): LocalDirectoryAuthorization =
    (context.applicationContext as io.github.chenxiex.calibrecloud.CalibreCloudApplication).dependencies.localAuthorization
