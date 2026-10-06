package io.github.chenxiex.calibrecloud.storage.local

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import java.io.File
import java.io.InputStream

/** AOSP external-storage documents only; no source filesystem conversion or writable open mode. */
class AndroidLocalDocumentAccess(context: Context) : LocalDocumentAccess {
    private val resolver = context.contentResolver
    private val permissions = AndroidDirectoryPermissions(context)

    override fun root(treeUri: String): LocalDocument {
        val location = permissions.localLocation(treeUri)
            ?: throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
        if (!permissions.persistedGrant(treeUri).readable) throw SecurityException()
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri.toUri(), location.treeDocumentId)
        return query(uri).singleOrNull() ?: throw LocalSourceException(StorageErrorKind.SOURCE_MISSING)
    }

    override fun children(treeUri: String, parentId: String): List<LocalDocument> {
        root(treeUri)
        requireWithinRoot(treeUri, parentId)
        return query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri.toUri(), parentId))
    }

    override fun isWithinRoot(treeUri: String, documentId: String): Boolean {
        val rootId = permissions.localLocation(treeUri)?.treeDocumentId ?: return false
        // IDs of the supported external-storage provider are volume:relative/path. Strict segment
        // checking complements tree-scoped URIs and rejects a forged sibling or traversal ID.
        if (!documentId.contains(':') || documentId.substringAfter(':').split('/').any { it == "." || it == ".." }) return false
        return documentId == rootId || documentId.startsWith(if (rootId.endsWith(':')) rootId else "$rootId/")
    }

    override fun openRead(treeUri: String, documentId: String): InputStream {
        root(treeUri)
        requireWithinRoot(treeUri, documentId)
        return resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri.toUri(), documentId))
            ?: throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
    }

    private fun requireWithinRoot(treeUri: String, documentId: String) {
        if (!isWithinRoot(treeUri, documentId)) throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
    }

    private fun query(uri: Uri): List<LocalDocument> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
                    val name = it.getString(1) ?: throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
                    add(LocalDocument(id, name, it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        it.getInt(3) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0))
                }
            }
        }
    }
}

/** SQLite is opened only against the copied private file and exclusively with OPEN_READONLY. */
class AndroidSnapshotValidator : SnapshotValidator {
    override fun validate(file: File): Boolean = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { database ->
            database.rawQuery("PRAGMA integrity_check", null).use { result ->
                result.moveToFirst() && result.getString(0) == "ok" && !result.moveToNext()
            }
        }
    } catch (_: SQLiteException) {
        false
    }
}
