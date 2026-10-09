package io.github.chenxiex.calibrecloud.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.BackendKind
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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        // The book provider resolves only published generations, never the state database.
        val forged = android.net.Uri.parse("content://${context.packageName}.books/$databaseName")
        assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openInputStream(forged)?.close() }
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
        val incompatible = original.copy(location = requireNotNull(candidate.location), generation = UUID.randomUUID())

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
    fun libraryListKeepsOrderAndAccessKeysAndSwitchesOnlyToListedLibraries() = runBlocking<Unit> {
        val first = localIdentity().location
        val second = LibraryLocation.OneDrive("account", "drive", "root")
        val firstSelection = repository.addLibrary(first, "content://test.documents/tree/first", "第一")
        repository.addLibrary(second, name = "第二")
        assertEquals(listOf(ConfiguredLibrary(first, "第一", "content://test.documents/tree/first"),
            ConfiguredLibrary(second, "第二", null)), repository.libraries())
        assertEquals(second, repository.current()!!.location)
        val switched = requireNotNull(repository.switchTo(first))
        assertEquals(first, switched.location)
        assertNotEquals(firstSelection.token, switched.token)
        assertNull(repository.switchTo(localIdentity().location))
        assertEquals(switched, repository.current())
        // Adding a listed location again refreshes its entry instead of listing it twice.
        val again = repository.beginAddition(first.backend, null).first
        repository.chooseAddition(again.token, first, "改名", "content://test.documents/tree/first-again").getOrThrow()
        val (selection, released) = requireNotNull(repository.completeAddition(again.token))
        assertEquals(first, selection.location)
        assertEquals("content://test.documents/tree/first", released)
        assertEquals(listOf(first, second), repository.libraries().map { it.location })
        assertEquals("content://test.documents/tree/first-again", repository.accessKey(first))
        assertEquals("改名", repository.libraries().first().displayName)
        assertEquals("content://test.documents/tree/first-again",
            repository.replaceAccessKey(first, "content://test.documents/tree/first").getOrNull())
        assertTrue(repository.accessKeyInUse("content://test.documents/tree/first"))
        assertFalse(repository.accessKeyInUse("content://test.documents/tree/first-again"))
        database.close()
        openDatabase()
        assertEquals(listOf(first, second), repository.libraries().map { it.location })
        assertEquals(first, repository.current()!!.location)
    }

    @Test
    fun anAdditionChangesNothingCurrentUntilCompletedAndCanBeAbandoned() = runBlocking<Unit> {
        val listed = repository.addLibrary(localIdentity().location, "content://test.documents/tree/listed")
        val location = localIdentity().location
        val addition = repository.beginAddition(location.backend, null).first
        assertTrue(repository.chooseAddition(addition.token, location, "新书库", "content://test.documents/tree/new").isSuccess)
        assertEquals(listed, repository.current())
        assertTrue(repository.accessKeyInUse("content://test.documents/tree/new"))
        assertEquals(location, repository.clearAdditionRoot(addition.token)!!.location)
        assertNull(repository.addition()!!.location)
        assertNull(repository.completeAddition(addition.token))
        // A new addition replaces the old one; the old token can no longer choose or complete.
        val (replacement, replaced) = repository.beginAddition(BackendKind.ONEDRIVE, UUID.randomUUID())
        assertEquals(addition.token, replaced!!.token)
        assertTrue(repository.chooseAddition(addition.token, location, null, null).isFailure)
        assertEquals(replacement, repository.cancelAddition())
        assertNull(repository.addition())
        assertEquals(listed, repository.current())
        assertEquals(1, repository.libraries().size)
    }

    @Test
    fun versionElevenLocalGrantMovesToTheListedCurrentLibrary() = runBlocking<Unit> {
        val location = localIdentity().location
        repository.addLibrary(location, "content://test.documents/tree/upgraded")
        StateSchemaHistory.downgrade(database.writableDatabase, 11)
        database.writableDatabase.rawQuery("SELECT tree_uri FROM local_authorization", null).use {
            assertTrue(it.moveToFirst()); assertEquals("content://test.documents/tree/upgraded", it.getString(0))
        }
        database.close()
        openDatabase()
        assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
        assertEquals(listOf(ConfiguredLibrary(location, null, "content://test.documents/tree/upgraded")), repository.libraries())
        assertEquals(location, repository.current()!!.location)
        assertNull(repository.addition())
    }

    @Test
    fun versionElevenSelectionWithoutLocationLeavesNoCurrentLibrary() = runBlocking<Unit> {
        repository.select(localIdentity().location)
        StateSchemaHistory.downgrade(database.writableDatabase, 11)
        database.writableDatabase.execSQL("UPDATE current_selection SET location_key = NULL")
        database.close()
        openDatabase()
        assertNull(repository.current())
        assertTrue(repository.libraries().isEmpty())
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

    @Test
    fun formatPriorityStartsWithEpubPersistsAndSurvivesVersionTenMigration() = runBlocking<Unit> {
        val epub = BookFormat.parse("EPUB")
        val pdf = BookFormat.parse("PDF")
        assertEquals(listOf(epub), repository.formatPriority())
        repository.setStartupEnabled(true)
        repository.setFormatPriority(listOf(pdf, epub))
        database.close()
        openDatabase()
        assertEquals(listOf(pdf, epub), repository.formatPriority())
        StateSchemaHistory.downgrade(database.writableDatabase, 10)
        database.close()
        openDatabase()
        assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
        // An upgraded installation keeps its startup choice and starts from the default order.
        assertTrue(repository.startupEnabled())
        assertEquals(listOf(epub), repository.formatPriority())
        expectIllegalArgument { repository.setFormatPriority(listOf(pdf, pdf)) }
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
