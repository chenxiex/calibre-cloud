package io.github.chenxiex.calibrecloud.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Production provider class over a UUID-named private test database and real files under
 * filesDir/books. The instance under test is attached with the installed provider's own declaration;
 * the declared authority and forged URIs also go through the installed provider.
 */
@RunWith(AndroidJUnit4::class)
class BookFileProviderTest {
    private lateinit var context: Context
    private lateinit var authority: String
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var provider: BookFileProvider
    private val libraryId = LibraryId(UUID.randomUUID())
    private val fixtureFiles = mutableListOf<File>()
    private val epub = BookFormat.parse("EPUB")

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        authority = "${context.packageName}.books"
        databaseName = "provider-test-${UUID.randomUUID()}.db"
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        val identity = LibraryIdentity(libraryId, LibraryLocation.Local("test.documents", "provider-${UUID.randomUUID()}"), UUID.randomUUID())
        assertTrue(state.bindValidated(state.select(identity.location).token, identity))
        val info = context.packageManager.resolveContentProvider(authority, 0)!!
        provider = BookFileProvider(StateBookCatalog(state, context.filesDir)).apply { attachInfo(this@BookFileProviderTest.context, info) }
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        fixtureFiles.asReversed().forEach { it.delete() }
        File(context.filesDir, "books/${libraryId.value}").delete()
    }

    private fun published(id: Long, title: String, text: String, format: BookFormat = epub, size: Long? = text.length.toLong()): DownloadedCopy {
        val location = CompleteCopyLocation(libraryId, UUID.randomUUID())
        fixture(PrivateBookFiles.file(context.filesDir, location), text)
        val copy = DownloadedCopy(CopyKey(BookKey(libraryId, id, UUID(0, id)), format), location, title, size,
            FileVersion(BackendKind.LOCAL, "v-${location.fileGeneration}"), SourceAvailability.AVAILABLE)
        assertTrue(runBlocking { state.publishComplete(copy) })
        return copy
    }

    private fun uriOf(path: String, copy: String) = Uri.Builder().scheme("content").authority(authority).appendPath(path)
        .appendQueryParameter("copy", copy).build()

    private fun read(uri: Uri) = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.reader().readText() }

    private fun row(uri: Uri): Pair<String, Long> = provider.query(uri, null, null, null, null).use {
        assertTrue(it.moveToFirst())
        it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
    }

    /** A URI that is not (or no longer) a published generation yields nothing, whichever exception reports it. */
    private fun assertNotServed(open: () -> Unit) {
        try {
            open()
            fail("URI was served")
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun publishedCopyIsServedUnderItsTitleFirstPathWithNameSizeAndType() {
        val copy = published(7, "三体：地球往事", "complete test copy")
        val uri = BookUris.contentUri(context, copy)

        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        assertEquals(listOf("三体：地球往事-7.epub"), uri.pathSegments)
        assertEquals("${libraryId.value}:${copy.location.fileGeneration}", uri.getQueryParameter("copy"))
        assertEquals("三体：地球往事-7.epub" to 18L, row(uri))
        assertEquals("application/epub+zip", provider.getType(uri))
        assertEquals("complete test copy", read(uri))
        // A projection limits the columns; unknown columns are not invented.
        provider.query(uri, arrayOf(OpenableColumns.SIZE, "_data"), null, null, null).use {
            assertEquals(listOf(OpenableColumns.SIZE), it.columnNames.toList())
        }
    }

    @Test
    fun untitledAndUnknownSizeCopiesStillHaveANameAndTheirFileSize() {
        val copy = published(42, "", "pdf bytes", BookFormat.parse("PDF"), size = null)
        val uri = BookUris.contentUri(context, copy)
        assertEquals(listOf("未命名书籍-42.pdf"), uri.pathSegments)
        assertEquals("未命名书籍-42.pdf" to 9L, row(uri))
        assertEquals("application/pdf", provider.getType(uri))
    }

    @Test
    fun booksWithTheSameTitleNeverShareANameOrBytes() {
        val first = published(1, "同名", "first")
        val second = published(2, "同名", "second")
        val a = BookUris.contentUri(context, first)
        val b = BookUris.contentUri(context, second)
        assertEquals(listOf("同名-1.epub"), a.pathSegments)
        assertEquals(listOf("同名-2.epub"), b.pathSegments)
        assertEquals("同名-1.epub", row(a).first)
        assertEquals("同名-2.epub", row(b).first)
        assertEquals("first", read(a))
        assertEquals("second", read(b))
    }

    @Test
    fun onlyTheCopyParameterLocatesAFileNotThePath() {
        val copy = published(8, "Path", "bytes")
        val uri = BookUris.contentUri(context, copy)
        // A different path segment names the same generation; the path is only a reader-facing name.
        assertEquals("bytes", read(uriOf("other-name.pdf", uri.getQueryParameter("copy")!!)))
        listOf(
            Uri.parse("content://$authority/${PrivateBookFiles.file(context.filesDir, copy.location).name}"),
            Uri.parse("content://$authority/books/${libraryId.value}/${copy.location.fileGeneration}.book"),
            uri.buildUpon().appendPath("extra").build(),
            uri.buildUpon().appendQueryParameter("other", "1").build(),
            uriOf("Path-8.epub", "${libraryId.value}:${copy.location.fileGeneration.toString().uppercase()}"),
            uriOf("Path-8.epub", "${libraryId.value}"),
            uriOf("Path-8.epub", "${libraryId.value}:${copy.location.fileGeneration}:x"),
        ).forEach { forged ->
            assertNotServed { provider.openFile(forged, "r") }
            assertNull(provider.getType(forged))
        }
    }

    @Test
    fun writeAccessIsRefusedEvenToThisApplication() {
        val copy = published(3, "Read only", "bytes")
        val uri = BookUris.contentUri(context, copy)
        listOf("w", "wt", "wa", "rw", "rwt").forEach { mode ->
            assertThrows(SecurityException::class.java) { provider.openFile(uri, mode) }
        }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.update(uri, android.content.ContentValues(), null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri, android.content.ContentValues()) }
        assertTrue(PrivateBookFiles.file(context.filesDir, copy.location).exists())
        assertEquals("bytes", read(uri))
    }

    @Test
    fun aReplacedGenerationIsNoLongerServedButAnOpenReaderFinishesTheOldBytes() {
        val old = published(4, "Updated", "old bytes")
        val oldUri = BookUris.contentUri(context, old)
        val reader = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(oldUri, "r"))
        val updated = published(4, "Updated", "new bytes!")

        assertFalse(PrivateBookFiles.file(context.filesDir, old.location).exists())
        assertEquals("old bytes", reader.use { it.reader().readText() })
        assertNotServed { provider.openFile(oldUri, "r") }
        provider.query(oldUri, null, null, null, null).use { assertEquals(0, it.count) }
        assertNull(provider.getType(oldUri))
        // An update keeps the reader-facing path, so readers that key on it see the same book.
        val newUri = BookUris.contentUri(context, updated)
        assertEquals(oldUri.pathSegments, newUri.pathSegments)
        assertEquals("new bytes!", read(newUri))
    }

    @Test
    fun wrongSizedUnpublishedAndLinkedFilesAreNotServed() {
        val damaged = published(5, "Damaged", "12345")
        PrivateBookFiles.file(context.filesDir, damaged.location).appendText("6")
        assertNotServed { provider.openFile(BookUris.contentUri(context, damaged), "r") }

        val stray = CompleteCopyLocation(libraryId, UUID.randomUUID())
        fixture(PrivateBookFiles.file(context.filesDir, stray), "crash orphan")
        assertNotServed { provider.openFile(uriOf("Stray-9.epub", "${libraryId.value}:${stray.fileGeneration}"), "r") }

        // A published generation whose file was swapped for a link to private data is refused.
        val linked = published(6, "Linked", "private")
        val secret = fixture(File(context.filesDir, "credentials/${UUID.randomUUID()}.token"), "private")
        val file = PrivateBookFiles.file(context.filesDir, linked.location)
        file.delete()
        Os.symlink(secret.absolutePath, file.absolutePath)
        assertNotServed { provider.openFile(BookUris.contentUri(context, linked), "r") }
    }

    @Test
    fun viewIntentGrantsOnlyTemporaryReadAccessToOneUri() {
        val copy = published(6, "Intent", "bytes")
        val intent = BookUris.viewIntent(context, copy)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(BookUris.contentUri(context, copy), intent.data)
        assertEquals("application/epub+zip", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val broader = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals(0, intent.flags and broader)
    }

    @Test
    fun forgedUrisAreNotServedByTheInstalledProvider() {
        val secret = fixture(File(context.filesDir, "credentials/${UUID.randomUUID()}.token"), "private")
        val staging = fixture(File(context.filesDir, "book-staging/${UUID.randomUUID()}.part"), "private")
        listOf(
            Uri.parse("content://$authority/credentials/${secret.name}"),
            Uri.parse("content://$authority/%2E%2E/book-staging/${staging.name}"),
            Uri.parse("content://$authority/${context.getDatabasePath(databaseName).name}"),
            uriOf("Unknown-1.epub", "${UUID.randomUUID()}:${UUID.randomUUID()}"),
        ).forEach { uri ->
            assertNotServed { context.contentResolver.openInputStream(uri)?.close() }
        }
    }

    @Test
    fun providerIsPrivateAndUsesTheDebugAuthority() {
        assertTrue(context.packageName.endsWith(".debug"))
        val info = context.packageManager.resolveContentProvider(authority, 0)!!

        assertEquals(context.packageName, info.packageName)
        assertEquals(BookFileProvider::class.java.name, info.name)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertNotEquals("io.github.chenxiex.calibrecloud.books", authority)
    }

    private fun fixture(file: File, text: String): File {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        check(!file.exists())
        file.writeText(text)
        fixtureFiles.add(file)
        return file
    }
}
