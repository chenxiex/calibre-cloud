package io.github.chenxiex.calibrecloud.files

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookFileProviderTest {
    private lateinit var context: Context
    private lateinit var authority: String
    private lateinit var fixtureId: String
    private val fixtureFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        authority = "${context.packageName}.books"
        fixtureId = "provider-test-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        fixtureFiles.asReversed().forEach { it.delete() }
    }

    @Test
    fun completedCopyHasContentUriAndReadableBytes() {
        val copy = fixture(File(context.filesDir, "books/$fixtureId.epub"))
        val uri = FileProvider.getUriForFile(context, authority, copy)

        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        assertEquals("books", uri.pathSegments.first())
        context.contentResolver.openInputStream(uri)!!.use {
            assertEquals("complete test copy", it.reader().readText())
        }
    }

    @Test
    fun privateDirectoriesCannotBeProvided() {
        val privateFiles = listOf(
            File(context.filesDir, "$fixtureId.epub"),
            File(context.filesDir, "staging/$fixtureId.part"),
            File(context.filesDir, "credentials/$fixtureId.token"),
            File(context.filesDir, "covers/$fixtureId.jpg"),
            File(context.filesDir, "books-other/$fixtureId.epub"),
            File(context.cacheDir, "$fixtureId.epub"),
            context.getDatabasePath("$fixtureId.db"),
        ).map(::fixture)

        privateFiles.forEach { file ->
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, file)
            }
        }
    }

    @Test
    fun traversalCannotGenerateUriOrReadOutsideBookDirectory() {
        fixture(File(context.filesDir, "staging/$fixtureId.part"))
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(
                context,
                authority,
                File(context.filesDir, "books/../staging/$fixtureId.part"),
            )
        }
        val forgedUri = Uri.parse("content://$authority/books/%2E%2E/staging/$fixtureId.part")
        assertThrows(SecurityException::class.java) {
            context.contentResolver.openInputStream(forgedUri)?.close()
        }
    }

    @Test
    fun symlinkCannotProvideFileOutsideBookDirectory() {
        val privateFile = fixture(File(context.filesDir, "credentials/$fixtureId.token"))
        val link = File(context.filesDir, "books/$fixtureId-link.epub")
        check(link.parentFile!!.isDirectory || link.parentFile!!.mkdirs())
        Os.symlink(privateFile.absolutePath, link.absolutePath)
        fixtureFiles.add(link)

        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, link)
        }
        val forgedUri = Uri.parse("content://$authority/books/${link.name}")
        assertThrows(SecurityException::class.java) {
            context.contentResolver.openInputStream(forgedUri)?.close()
        }
    }

    @Test
    fun providerIsPrivateAndUsesDebugAuthority() {
        assertTrue(context.packageName.endsWith(".debug"))
        val provider = context.packageManager.resolveContentProvider(authority, 0)!!

        assertEquals(context.packageName, provider.packageName)
        assertEquals(FileProvider::class.java.name, provider.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertFalse(authority == "io.github.chenxiex.calibrecloud.books")
    }

    private fun fixture(file: File): File {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        check(!file.exists())
        file.writeText("complete test copy")
        fixtureFiles.add(file)
        return file
    }
}
