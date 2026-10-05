package io.github.chenxiex.calibrecloud.state

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.CopyReadResult
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.cache.PrivateCopyReader
import io.github.chenxiex.calibrecloud.storage.local.DirectoryGrant
import io.github.chenxiex.calibrecloud.storage.local.DirectoryPermissionAccess
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryConfiguration
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real Android SQLite and only UUID-named private test databases and file generations. */
@RunWith(AndroidJUnit4::class)
class ApplicationStateRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var repository: ApplicationStateRepository
    private lateinit var files: PrivateBookFiles
    private val fixtureDirectories = mutableSetOf<File>()
    private val fixtureFiles = mutableSetOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "state-test-${UUID.randomUUID()}.db"
        files = PrivateBookFiles(context.filesDir)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        fixtureFiles.forEach { it.delete() }
        fixtureDirectories.forEach { it.deleteRecursively() }
    }

    @Test
    fun closeAndReopenRestoresSelectionBindingAndCompleteManifest() = runBlocking<Unit> {
        val identity = localIdentity()
        val selection = repository.select(identity.location)
        assertNull(selection.identity)
        assertTrue(repository.bindValidated(selection.token, identity))
        val copy = completeCopy(identity).copy(sizeBytes = null)
        repository.publishComplete(copy)
        val current = repository.current()

        database.close()
        openDatabase()

        assertEquals(current, repository.current())
        assertEquals(identity, repository.binding(identity.id))
        assertEquals(copy, repository.find(copy.key))
        assertEquals(listOf(copy), repository.listCopies(identity.id, limit = 10, offset = 0))
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.books", context.getDatabasePath(databaseName))
        }
    }

    @Test
    fun candidateSwitchRevokesOldSelectionAndPreservesBindingForValidatedReturn() = runBlocking<Unit> {
        val oldIdentity = localIdentity()
        val oldSelection = repository.select(oldIdentity.location)
        assertTrue(repository.bindValidated(oldSelection.token, oldIdentity))
        val remoteLocation = LibraryLocation.OneDrive("account", "drive", "root")
        val candidate = repository.select(remoteLocation)

        assertNull(candidate.identity)
        assertEquals(candidate, repository.current())
        assertFalse(repository.bindValidated(oldSelection.token, oldIdentity))
        assertEquals(candidate, repository.current())
        assertEquals(oldIdentity, repository.binding(oldIdentity.id))

        database.close()
        openDatabase()
        assertEquals(candidate, repository.current())
        assertNull(repository.current()!!.identity)
        assertEquals(oldIdentity, repository.binding(oldIdentity.id))

        val returned = repository.select(oldIdentity.location)
        assertNull(returned.identity)
        assertTrue(returned.token != oldSelection.token)
        assertFalse(repository.bindValidated(candidate.token, LibraryIdentity(
            LibraryId(UUID.randomUUID()), remoteLocation, UUID.randomUUID(),
        )))
        assertEquals(returned, repository.current())
        assertTrue(repository.bindValidated(returned.token, oldIdentity))
        assertEquals(oldIdentity, repository.current()!!.identity)
    }

    @Test
    fun replacementAtSameLocationKeepsOldGenerationAndItsCopiesIsolated() = runBlocking<Unit> {
        val original = localIdentity()
        bind(original)
        val oldCopy = completeCopy(original)
        repository.publishComplete(oldCopy)
        val replacement = LibraryIdentity(LibraryId(UUID.randomUUID()), original.location, UUID.randomUUID())
        bind(replacement)
        val newCopy = completeCopy(replacement, sourceUuid = oldCopy.key.book.sourceUuid)
        repository.publishComplete(newCopy)

        assertEquals(replacement, repository.current()!!.identity)
        assertEquals(original, repository.binding(original.id))
        assertEquals(setOf(original, replacement), repository.bindingsAt(original.location).toSet())
        assertEquals(oldCopy, repository.find(oldCopy.key))
        assertEquals(newCopy, repository.find(newCopy.key))
        assertEquals(listOf(oldCopy), repository.listCopies(original.id, 10, 0))
        assertEquals(listOf(newCopy), repository.listCopies(replacement.id, 10, 0))
    }

    @Test
    fun completeKeysSeparateLibrariesBookUuidsAndFormatsWithStablePagination() = runBlocking<Unit> {
        val first = localIdentity()
        val second = localIdentity()
        bind(first)
        bind(second)
        val sourceUuid = UUID.randomUUID()
        val firstEpub = completeCopy(first, sourceUuid = sourceUuid)
        val firstPdf = completeCopy(first, sourceUuid = sourceUuid, format = "PDF")
            .copy(sourceAvailability = SourceAvailability.AVAILABLE)
        val replacedBook = completeCopy(first)
        val secondEpub = completeCopy(second, sourceUuid = sourceUuid)
        listOf(firstPdf, secondEpub, replacedBook, firstEpub).forEach { repository.publishComplete(it) }

        listOf(firstEpub, firstPdf, replacedBook, secondEpub).forEach { assertEquals(it, repository.find(it.key)) }
        val all = repository.listCopies(first.id, 10, 0)
        assertEquals(setOf(firstEpub, firstPdf, replacedBook), all.toSet())
        assertEquals(
            all.sortedWith(compareBy<DownloadedCopy> { it.key.book.sourceId }
                .thenBy { it.key.book.sourceUuid.toString() }
                .thenBy { it.key.format.value }),
            all,
        )
        assertEquals(all, (0 until all.size).flatMap { repository.listCopies(first.id, 1, it) })
        assertTrue(repository.listCopies(first.id, 10, all.size).isEmpty())
        assertEquals(listOf(secondEpub), repository.listCopies(second.id, 10, 0))
        assertNull(repository.find(firstEpub.key.copy(format = BookFormat.parse("MOBI"))))
    }

    @Test
    fun incompatibleIdentityBindingRollsBackWithoutChangingCurrentOrOldBinding() = runBlocking<Unit> {
        val original = localIdentity()
        bind(original)
        val candidate = repository.select(localIdentity().location)
        val incompatible = original.copy(location = candidate.location, generation = UUID.randomUUID())

        expectIllegalArgument { repository.bindValidated(candidate.token, incompatible) }

        assertEquals(candidate, repository.current())
        assertEquals(original, repository.binding(original.id))
        database.close()
        openDatabase()
        assertEquals(candidate, repository.current())
        assertEquals(original, repository.binding(original.id))
    }

    @Test
    fun uncommittedManifestTransactionRollsBackAcrossDatabaseReopen() = runBlocking<Unit> {
        val identity = localIdentity()
        bind(identity)
        val copy = completeCopy(identity)
        repository.publishComplete(copy)
        val sqlite = database.writableDatabase
        sqlite.beginTransaction()
        try {
            sqlite.execSQL(
                "UPDATE downloaded_copies SET title = ? WHERE library_id = ?",
                arrayOf("uncommitted title", identity.id.value.toString()),
            )
            sqlite.rawQuery("SELECT title FROM downloaded_copies WHERE library_id = ?", arrayOf(identity.id.value.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals("uncommitted title", it.getString(0))
            }
            // Deliberately omit setTransactionSuccessful, as a failed multi-stage state mutation would.
        } finally {
            sqlite.endTransaction()
        }
        database.close()
        openDatabase()

        assertEquals(copy, repository.find(copy.key))
        assertEquals(identity, repository.current()!!.identity)
    }

    @Test
    fun localConfigurationImportsLegacyOnceAndCommitsGrantReferenceWithCandidate() = runBlocking<Unit> {
        val oldLocation = localIdentity().location as LibraryLocation.Local
        val newLocation = localIdentity().location as LibraryLocation.Local
        val oldUri = "content://test.documents/tree/old-test-library"
        val newUri = "content://test.documents/tree/new-test-library"
        val locations = mapOf(oldUri to oldLocation, newUri to newLocation)
        var legacyLoads = 0
        val permissions = object : DirectoryPermissionAccess {
            override fun localLocation(treeUri: String) = locations[treeUri]
            override fun persist(treeUri: String, resultFlags: Int) = error("Configuration must not acquire a grant")
            override fun persistedGrant(treeUri: String): DirectoryGrant = error("Configuration must not inspect grants")
            override fun release(treeUri: String) = error("Configuration must not release a grant")
        }
        val legacy = object : LocalDirectoryConfiguration {
            override fun load(): String {
                legacyLoads++
                return oldUri
            }
            override fun save(treeUri: String): Boolean = error("Migration must not rewrite legacy preferences")
        }
        val configuration = DatabaseLocalDirectoryConfiguration(repository, permissions, legacy)

        assertEquals(oldUri, configuration.load())
        assertEquals(oldLocation, repository.current()!!.location)
        assertNull(repository.current()!!.identity)
        val remote = repository.select(LibraryLocation.OneDrive("account", "drive", "root"))
        assertEquals(oldUri, configuration.load())
        assertEquals(remote, repository.current())
        assertEquals(1, legacyLoads)
        assertTrue(configuration.save(newUri))
        val candidate = repository.current()!!
        assertEquals(newLocation, candidate.location)
        assertNull(candidate.identity)
        assertFalse(configuration.save("content://unsupported/tree/invalid"))
        assertEquals(candidate, repository.current())
        database.close()
        openDatabase()

        val reopened = DatabaseLocalDirectoryConfiguration(repository, permissions, legacy)
        assertEquals(newUri, reopened.load())
        assertEquals(candidate, repository.current())
        assertEquals(1, legacyLoads)
    }

    @Test
    fun stagingMissingAndCorruptFilesCannotPublishCompleteRecords() = runBlocking<Unit> {
        val identity = localIdentity()
        bind(identity)
        val copy = completeCopy(identity)
        val path = completeFile(copy)
        assertTrue(path.delete())
        val staging = File(context.filesDir, "staging/state-test-${UUID.randomUUID()}.part")
        check(staging.parentFile!!.isDirectory || staging.parentFile!!.mkdirs())
        staging.writeText(CONTENT)
        fixtureFiles.add(staging)

        expectIllegalArgument { repository.publishComplete(copy) }
        assertNull(repository.find(copy.key))
        assertTrue(repository.listCopies(identity.id, 10, 0).isEmpty())
        path.writeText("x")
        expectIllegalArgument { repository.publishComplete(copy) }
        assertNull(repository.find(copy.key))
        path.writeText("")
        expectIllegalArgument { repository.publishComplete(copy.copy(sizeBytes = null)) }
        assertNull(repository.find(copy.key))
    }

    @Test
    fun failedReplacementPublicationPreservesOldRecordAndReadableGeneration() = runBlocking<Unit> {
        val identity = localIdentity()
        bind(identity)
        val original = completeCopy(identity)
        repository.publishComplete(original)
        val replacement = completeCopy(identity, sourceUuid = original.key.book.sourceUuid)
        completeFile(replacement).writeText("x")

        expectIllegalArgument { repository.publishComplete(replacement) }

        database.close()
        openDatabase()
        assertEquals(original, repository.find(original.key))
        val result = PrivateCopyReader(repository, files, Dispatchers.IO).read(original.key) as CopyReadResult.Available
        result.handle.use { assertEquals(CONTENT, it.input.reader().readText()) }
    }

    @Test
    fun missingAndCorruptBytesDoNotErasePublishedManifest() = runBlocking<Unit> {
        val identity = localIdentity()
        bind(identity)
        val copy = completeCopy(identity)
        repository.publishComplete(copy)
        val reader = PrivateCopyReader(repository, files, Dispatchers.IO)
        assertTrue(completeFile(copy).delete())

        assertEquals(CopyReadResult.Missing, reader.read(copy.key))
        assertEquals(copy, repository.find(copy.key))
        val unrecorded = copy.key.copy(book = copy.key.book.copy(sourceUuid = UUID.randomUUID()))
        assertEquals(CopyReadResult.Missing, reader.read(unrecorded))
        assertNull(repository.find(unrecorded))

        completeFile(copy).writeText("x")
        val result = reader.read(copy.key) as CopyReadResult.Failed
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, result.error.kind)
        assertEquals(copy, repository.find(copy.key))
    }

    @Test
    fun ordinaryReadUsesPrivateBytesEvenWhenSourceIsConfirmedMissing() = runBlocking<Unit> {
        val location = LibraryLocation.OneDrive("offline-account", "unreachable-drive", "missing-root")
        val identity = LibraryIdentity(LibraryId(UUID.randomUUID()), location, UUID.randomUUID())
        bind(identity)
        val copy = completeCopy(identity).copy(sourceAvailability = SourceAvailability.CONFIRMED_MISSING)
        repository.publishComplete(copy)
        database.close()
        openDatabase()

        val result = PrivateCopyReader(repository, files, Dispatchers.IO).read(copy.key) as CopyReadResult.Available
        result.handle.use { assertEquals(CONTENT, it.input.reader().readText()) }
        assertEquals(copy.savedVersion, result.version)
        assertEquals(copy, repository.find(copy.key))
    }

    private fun openDatabase() {
        database = ApplicationStateDatabase(context, databaseName)
        repository = ApplicationStateRepository(database, files, Dispatchers.IO)
    }

    private fun localIdentity() = LibraryIdentity(
        LibraryId(UUID.randomUUID()),
        LibraryLocation.Local("test.documents", "library-${UUID.randomUUID()}"),
        UUID.randomUUID(),
    )

    private suspend fun bind(identity: LibraryIdentity) {
        assertTrue(repository.bindValidated(repository.select(identity.location).token, identity))
    }

    private fun completeCopy(
        identity: LibraryIdentity,
        sourceUuid: UUID = UUID.randomUUID(),
        format: String = "EPUB",
    ): DownloadedCopy {
        val copy = DownloadedCopy(
            CopyKey(BookKey(identity.id, 1L, sourceUuid), BookFormat.parse(format)),
            CompleteCopyLocation(identity.id, UUID.randomUUID()),
            "Test title $format",
            CONTENT.toByteArray().size.toLong(),
            FileVersion(identity.location.backend, "test-version"),
            SourceAvailability.UNCONFIRMED,
        )
        val file = completeFile(copy)
        val directory = file.parentFile!!
        check(directory.isDirectory || directory.mkdirs())
        fixtureDirectories.add(directory)
        check(!file.exists())
        file.writeText(CONTENT)
        return copy
    }

    private fun completeFile(copy: DownloadedCopy) = File(
        context.filesDir,
        "books/${copy.location.libraryId.value}/${copy.location.fileGeneration}.book",
    )

    private suspend fun expectIllegalArgument(action: suspend () -> Any?) {
        try {
            action()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Invalid publication or binding must leave all persisted state unchanged.
        }
    }

    private companion object {
        const val CONTENT = "complete private test copy"
    }
}
