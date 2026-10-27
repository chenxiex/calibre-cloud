package io.github.chenxiex.calibrecloud.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.storage.local.AndroidDirectoryPermissions
import io.github.chenxiex.calibrecloud.ui.MainActivity
import io.github.chenxiex.calibrecloud.ui.MoreTarget
import io.github.chenxiex.calibrecloud.ui.OpenNotifications
import java.io.IOException

private const val ASSET_DIRECTORY = "koreader/$PLUGIN_DIRECTORY"

/** The plugin files the build copies from `external/koreader` into the APK's assets. */
fun bundledPlugin(context: Context): BundledPlugin {
    val assets = context.assets
    val names = assets.list(ASSET_DIRECTORY).orEmpty()
    return BundledPlugin(names.map { name -> PluginFile(name, assets.open("$ASSET_DIRECTORY/$name").use { it.readBytes() }) })
}

/**
 * Where the picker starts: KOReader keeps its data in `koreader` on the primary shared storage. Only a
 * hint; the user may pick any folder.
 */
val KOREADER_INITIAL_URI: Uri =
    DocumentsContract.buildDocumentUri(AndroidDirectoryPermissions.LOCAL_AUTHORITY, "primary:koreader")

/** Folders of the system external storage provider only, like local libraries. */
class SafKoreaderFolders(context: Context) : KoreaderFolders {
    private val resolver = context.contentResolver
    private val permissions = AndroidDirectoryPermissions(context)

    override fun open(treeUri: String): KoreaderFolder? {
        if (permissions.localLocation(treeUri) == null) return null
        val grant = permissions.persistedGrant(treeUri)
        if (!grant.readable || !grant.writable) return null
        return SafKoreaderFolder(resolver, treeUri.toUri())
    }

    override fun persist(treeUri: String, resultFlags: Int) {
        if (permissions.localLocation(treeUri) == null || resultFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            throw SecurityException()
        }
        permissions.persist(treeUri, resultFlags)
    }

    override fun release(treeUri: String) = permissions.release(treeUri)
}

private class SafKoreaderFolder(private val resolver: ContentResolver, private val tree: Uri) : KoreaderFolder {
    private fun document(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun root(): FolderEntry? {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return try {
            resolver.query(document(id), arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use {
                if (it.moveToFirst()) FolderEntry(id, it.getString(0) == Document.MIME_TYPE_DIR) else null
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.io.FileNotFoundException) {
            null
        }
    }

    override fun child(parent: FolderEntry, name: String): FolderEntry? =
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(1) == name) return@use FolderEntry(it.getString(0), it.getString(2) == Document.MIME_TYPE_DIR)
            }
            null
        }

    override fun createDirectory(parent: FolderEntry, name: String) = FolderEntry(create(parent, Document.MIME_TYPE_DIR, name), true)

    override fun write(parent: FolderEntry, name: String, content: ByteArray) {
        val existing = child(parent, name)
        if (existing?.directory == true) throw IOException("not a file")
        val id = existing?.id ?: create(parent, "application/octet-stream", name)
        (resolver.openOutputStream(document(id), "wt") ?: throw IOException("no stream")).use { it.write(content) }
    }

    override fun read(file: FolderEntry): ByteArray =
        (resolver.openInputStream(document(file.id)) ?: throw IOException("no stream")).use { it.readBytes() }

    /** The provider renames a clashing or unusual name; a different result is removed and fails. */
    private fun create(parent: FolderEntry, mimeType: String, name: String): String {
        val created = DocumentsContract.createDocument(resolver, document(parent.id), mimeType, name) ?: throw IOException("not created")
        val actual = resolver.query(created, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (actual != name) {
            DocumentsContract.deleteDocument(resolver, created)
            throw IOException("created under another name")
        }
        return DocumentsContract.getDocumentId(created)
    }
}

/** One replaceable notice that the installed plugin is not the bundled one (Q98); tapping it opens the settings. */
internal object KoreaderPluginNotifications {
    private const val CHANNEL = "koreader-plugin"
    private const val NOTIFICATION = 14

    fun post(context: Context, bundledVersion: String) {
        if (!OpenNotifications.allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.koreader_plugin_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
        val target = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OpenNotifications.EXTRA_MORE_TARGET, MoreTarget.EXTERNAL_CONTROL)
        val intent = PendingIntent.getActivity(context, NOTIFICATION, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(R.string.koreader_plugin_update_text, bundledVersion)
        manager.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.koreader_plugin_update_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build())
    }

    fun withdraw(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
}
