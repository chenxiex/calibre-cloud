package io.github.chenxiex.calibrecloud.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** Published complete copies the provider may serve, addressed by their immutable file generation. */
interface BookCatalog {
    /** The manifest record of [location]; null once the generation was replaced or removed. */
    fun describe(location: CompleteCopyLocation): DownloadedCopy?

    /** A read-only descriptor of a still published generation, or null when it is not served. */
    fun open(location: CompleteCopyLocation): ParcelFileDescriptor?
}

/**
 * Looks generations up in the shared manifest and opens them under the same copy lock as ordinary
 * reads, so a generation that cleanup or an update has retired is never handed out again. A reader
 * that already holds a descriptor keeps reading the old bytes after the file is unlinked.
 */
class StateBookCatalog(private val state: ApplicationStateRepository, private val filesDir: File) : BookCatalog {
    override fun describe(location: CompleteCopyLocation): DownloadedCopy? = runBlocking { state.findAt(location) }

    override fun open(location: CompleteCopyLocation): ParcelFileDescriptor? = runBlocking {
        state.copyAccess.withLock {
            val copy = state.findAt(location) ?: return@withLock null
            openReadOnly(PrivateBookFiles.file(filesDir, location), copy.sizeBytes)
        }
    }

    /** Opens without following links; a missing, empty, irregular or wrong-sized file is not served. */
    private fun openReadOnly(file: File, expectedSize: Long?): ParcelFileDescriptor? {
        val directory = file.parentFile!!
        if (Files.isSymbolicLink(directory.parentFile!!.toPath()) || Files.isSymbolicLink(directory.toPath())) {
            throw SecurityException("Linked book cache")
        }
        val descriptor = try {
            Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        } catch (error: ErrnoException) {
            when (error.errno) {
                OsConstants.ENOENT -> return null
                OsConstants.ELOOP -> throw SecurityException("Linked book copy")
                else -> throw IOException("Book copy open failed")
            }
        }
        try {
            val status = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(status.st_mode) || status.st_size <= 0 || (expectedSize != null && status.st_size != expectedSize)) {
                return null
            }
            return ParcelFileDescriptor.dup(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}

/**
 * Read-only provider for complete book copies under `${applicationId}.books`. A URI names one
 * published generation only through its `copy=<LibraryId>:<generation>` query parameter; the single
 * path segment ("title-ID.ext") exists because some readers name their imported file and shelf entry
 * after the URI path, and it is never used to find a file. Display name, size and MIME type come from
 * the manifest record. No URI maps to an arbitrary path, and write access is never served, even to
 * this application.
 */
class BookFileProvider internal constructor(private val injected: BookCatalog?) : ContentProvider() {
    constructor() : this(null)

    private val catalog: BookCatalog by lazy {
        injected ?: (context!!.applicationContext as CalibreCloudApplication).dependencies.bookCatalog
    }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        // Access is only ever granted per URI.
        if (info.exported) throw SecurityException("Book provider must not be exported")
        if (!info.grantUriPermissions) throw SecurityException("Book provider must grant URI permissions")
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = BookUris.locate(uri)?.let(catalog::describe)?.let(BookUris::mimeType)

    /** One row for a published generation, no rows otherwise. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        val cursor = MatrixCursor(columns.toTypedArray(), 1)
        val location = BookUris.locate(uri) ?: return cursor
        val copy = catalog.describe(location) ?: return cursor
        val size = copy.sizeBytes ?: PrivateBookFiles.file(context!!.filesDir, location).length().takeIf { it > 0 }
        cursor.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) BookUris.displayName(context!!, copy) else size })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Book copies are read-only")
        val location = BookUris.locate(uri) ?: throw FileNotFoundException("Unknown book URI")
        return catalog.open(location) ?: throw FileNotFoundException("Book copy is no longer available")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Book copies are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Book copies are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Book copies are read-only")
}

/** Builds what is handed to a reader: the provider URI, display name, MIME type and a read-only view intent. */
object BookUris {
    private const val COPY = "copy"

    fun authority(context: Context) = "${context.packageName}.books"

    private fun untitled(context: Context, copy: DownloadedCopy) = context.getString(R.string.book_untitled_name, copy.key.book.sourceId)

    /** The display name, identical to the URI path segment. */
    fun displayName(context: Context, copy: DownloadedCopy): String =
        BookFileNames.readerName(copy.title, copy.key.book.sourceId, copy.key.format, untitled(context, copy))

    fun mimeType(copy: DownloadedCopy): String =
        BookMimeTypes.of(copy.key.format) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    fun contentUri(context: Context, copy: DownloadedCopy): Uri = Uri.Builder().scheme("content").authority(authority(context))
        .appendPath(displayName(context, copy))
        .appendQueryParameter(COPY, "${copy.location.libraryId.value}:${copy.location.fileGeneration}")
        .build()

    /** The generation a URI names: one path segment and the canonical spelling of both UUIDs, nothing else. */
    fun locate(uri: Uri): CompleteCopyLocation? {
        if (uri.scheme != "content" || uri.pathSegments.size != 1 || uri.queryParameterNames != setOf(COPY)) return null
        val value = uri.getQueryParameter(COPY) ?: return null
        val parts = value.split(':')
        if (parts.size != 2) return null
        return try {
            CompleteCopyLocation(LibraryId(UUID.fromString(parts[0])), UUID.fromString(parts[1]))
                .takeIf { "${it.libraryId.value}:${it.fileGeneration}" == value }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Grants the receiving reader temporary read access to this one URI and nothing else. */
    fun viewIntent(context: Context, copy: DownloadedCopy): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(context, copy), mimeType(copy))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
