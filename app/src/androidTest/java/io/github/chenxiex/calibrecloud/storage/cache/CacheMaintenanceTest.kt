package io.github.chenxiex.calibrecloud.storage.cache

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.CalibreFixture
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.StateSchemaHistory
import io.github.chenxiex.calibrecloud.state.addLibrary
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import io.github.chenxiex.calibrecloud.library.LibraryFilters
import io.github.chenxiex.calibrecloud.library.LibraryQueryService
import io.github.chenxiex.calibrecloud.library.MetadataLibraryImports
import io.github.chenxiex.calibrecloud.library.StateLibraryCopies
import io.github.chenxiex.calibrecloud.state.SearchHistoryStore
import io.github.chenxiex.calibrecloud.tasks.copies.CopyService
import io.github.chenxiex.calibrecloud.ui.LibraryContent
import io.github.chenxiex.calibrecloud.ui.LibraryCovers
import io.github.chenxiex.calibrecloud.ui.LibraryViewModel
import io.github.chenxiex.calibrecloud.ui.QueueLibraryBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Real SQLite and private files; these fixtures never authorize or access source storage. */
@RunWith(AndroidJUnit4::class)
class CacheMaintenanceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var files: File
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var bookFiles: PrivateBookFiles
    private lateinit var state: ApplicationStateRepository
    private lateinit var metadata: MetadataRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var maintenance: CacheMaintenance
    private lateinit var book: BookKey

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "cleanup-test-${UUID.randomUUID()}").apply { mkdirs() }
        files = File(root, "files").apply { mkdirs() }
        databaseName = "cleanup-test-${UUID.randomUUID()}.db"
        reopen()
        book = activate("first")
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    @Test
    fun removingEpubPreservesPdfManifestBytesTaskAndCheckpointAcrossReopen() = runBlocking<Unit> {
        val epub = publish(book, "EPUB")
        val pdf = publish(book, "PDF")
        val epubTask = pendingCopy(epub.key)
        val pdfTask = pendingCopy(pdf.key)
        val pendingPdfFile = renamedCopy(pdfTask, pdf.key)
        val pendingEpubFile = renamedCopy(epubTask, epub.key)
        val pdfEntry = queue.get(pdfTask)
        val formats = mutableSetOf(BookFormat.parse("EPUB"))
        val plan = requireNotNull(maintenance.previewCopies(setOf(book), formats))
        formats.clear()
        formats.add(BookFormat.parse("PDF"))
        assertEquals(setOf(epub.key), plan.copies)
        assertTrue(plan.bytes >= requireNotNull(epub.sizeBytes))
        assertTrue(maintenance.execute(plan))
        assertNull(state.find(epub.key))
        assertFalse(copyFile(epub).exists())
        assertFalse(pendingEpubFile.exists())
        assertEquals("PDF pending bytes", pendingPdfFile.readText())
        assertCancelled(epubTask)
        assertFalse(staging(epubTask).exists())
        assertEquals(pdf, state.find(pdf.key))
        assertEquals("PDF bytes", copyFile(pdf).readText())
        assertEquals(pdfEntry, queue.get(pdfTask))
        assertTrue(staging(pdfTask).isDirectory)
        database.close()
        reopen()
        assertNull(state.find(epub.key))
        assertEquals(pdf, state.find(pdf.key))
        assertEquals(pdfEntry, queue.get(pdfTask))
        assertEquals("PDF pending bytes", pendingPdfFile.readText())
    }

    /** R26 through the library page: the format filter at preview is the removal scope, whatever happens after. */
    @Test
    fun selectionRemovalUnderAnEpubFilterKeepsThePdfCopyAndItsTask() = runBlocking<Unit> {
        val epub = publish(book, "EPUB")
        val pdf = publish(book, "PDF")
        val epubTask = pendingCopy(epub.key)
        val pdfTask = pendingCopy(pdf.key)
        val pdfEntry = queue.get(pdfTask)
        val queries = LibraryQueryService(MetadataLibraryImports(metadata), StateLibraryCopies(state), Dispatchers.Default)
        val batch = QueueLibraryBatch(CopyService(state, metadata, TaskCoordinator(queue, emptyList()), queue),
            TaskCoordinator(queue, emptyList()), maintenance)
        val model = withContext(Dispatchers.Main) {
            LibraryViewModel(state::current, queries, NoCovers, emptyFlow(), NoHistory, batch).apply {
                setVisible(true)
                onMeasured(10)
                updateFilters { LibraryFilters(formats = setOf(epub.key.format)) }
            }
        }
        suspend fun <T> onMain(read: LibraryViewModel.() -> T) = withContext(Dispatchers.Main) { model.read() }
        suspend fun until(condition: LibraryViewModel.() -> Boolean) = withTimeout(10_000) {
            while (!onMain(condition)) delay(20)
        }
        until { (content as? LibraryContent.Books)?.rows?.size == 1 }
        onMain { toggleBook(book) }
        until { selectedBooks == 1 }
        onMain { prepareRemoval() }
        until { removal != null }
        val shown = onMain { requireNotNull(removal) }
        assertEquals(setOf(epub.key), shown.plan.copies)
        assertEquals(setOf(epub.key.format), shown.formats)
        onMain { confirmRemoval() }
        until { selected == null && !batchBusy }
        assertNull(state.find(epub.key))
        assertFalse(copyFile(epub).exists())
        assertCancelled(epubTask)
        assertEquals(pdf, state.find(pdf.key))
        assertEquals("PDF bytes", copyFile(pdf).readText())
        assertEquals(pdfEntry, queue.get(pdfTask))
        // Without the filter the kept PDF is the book's downloaded default again.
        onMain { updateFilters { LibraryFilters() } }
        until { (content as? LibraryContent.Books)?.rows?.singleOrNull()?.defaultFormat?.format == pdf.key.format }
        assertTrue(onMain { (content as LibraryContent.Books).rows.single().downloaded })
        withContext(Dispatchers.Main) { model.setVisible(false) }
    }

    private object NoCovers : LibraryCovers {
        override suspend fun read(book: BookKey): android.graphics.Bitmap? = null
        override suspend fun request(books: List<BookKey>, selectionToken: UUID): TaskId? = null
        override fun changes(task: TaskId): kotlinx.coroutines.flow.Flow<io.github.chenxiex.calibrecloud.tasks.api.TaskState> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun wake() {}
    }

    private object NoHistory : SearchHistoryStore {
        override suspend fun list(libraryId: LibraryId) = emptyList<String>()
        override suspend fun record(libraryId: LibraryId, query: String) {}
        override suspend fun clear(libraryId: LibraryId) {}
    }

    @Test
    fun removalRevokesNewReadsAndRetiresTheGenerationAfterExistingHandleCloses() = runBlocking<Unit> {
        val copy = publish(book, "EPUB")
        val reader = PrivateCopyReader(state, bookFiles, Dispatchers.IO, state.copyAccess)
        val opened = reader.read(copy.key) as CopyReadResult.Available
        try {
            assertTrue(maintenance.execute(requireNotNull(maintenance.previewCopies(setOf(book)))))
            assertEquals(CopyReadResult.Missing, reader.read(copy.key))
            assertTrue(copyFile(copy).isFile)
            assertEquals("EPUB bytes", opened.handle.input.bufferedReader().readText())
        } finally { opened.handle.close() }
        assertFalse(copyFile(copy).exists())
    }

    @Test
    fun multipleFormatsAndNoFilterRemoveAllMatchingCopiesIncludingPendingOnlyFormats() = runBlocking<Unit> {
        val epub = publish(book, "EPUB")
        val pdf = publish(book, "PDF")
        val mobi = pendingCopy(CopyKey(book, BookFormat.parse("MOBI")))
        val narrow = requireNotNull(maintenance.previewCopies(setOf(book), setOf(epub.key.format, pdf.key.format)))
        assertTrue(maintenance.execute(narrow))
        assertNull(state.find(epub.key))
        assertNull(state.find(pdf.key))
        assertTrue(queue.get(mobi)!!.record.state is TaskState.Paused)
        val all = requireNotNull(maintenance.previewCopies(setOf(book)))
        assertTrue(maintenance.execute(all))
        assertCancelled(mobi)
        assertFalse(staging(mobi).exists())
    }

    @Test
    fun clearingMetadataKeepsCompleteCopiesSelectionAndUnresolvedWriteEvidence() = runBlocking<Unit> {
        val copy = publish(book, "EPUB")
        val pendingPdf = pendingCopy(CopyKey(book, BookFormat.parse("PDF")))
        val pendingPdfFile = renamedCopy(pendingPdf, CopyKey(book, BookFormat.parse("PDF")))
        val pendingPdfEntry = queue.get(pendingPdf)
        val selected = state.current()
        val imported = requireNotNull(metadata.currentImport())
        val column = CustomColumnId(1, "#finished")
        assertTrue(metadata.selectReadColumn(imported, column))
        val snapshot = File(files, "metadata/${imported.generation}/metadata.db")
        assertTrue(snapshot.isFile)
        val cover = cover(book)
        val sync = submit(TaskRequest.MetadataSync(book.libraryId), TaskOrigin.MANUAL_SYNC)
        val image = submit(TaskRequest.CoverLoad(book), TaskOrigin.VISIBLE_COVER)
        val write = protectedWrite(book)
        val protected = File(files, "write-recovery/${UUID.randomUUID()}/backup.db").apply {
            parentFile!!.mkdirs(); writeText("protected recovery backup")
        }
        val writeEntry = queue.get(write)
        assertTrue(maintenance.execute(requireNotNull(maintenance.previewMetadata())))
        assertNull(metadata.currentImport())
        assertEquals(0L, count("metadata_books"))
        assertEquals(0L, count("cover_cache"))
        assertFalse(snapshot.exists())
        assertFalse(cover.exists())
        assertCancelled(sync)
        assertCancelled(image)
        assertEquals(writeEntry, queue.get(write))
        assertEquals("protected recovery backup", protected.readText())
        assertEquals(selected, state.current())
        assertEquals(pendingPdfEntry, queue.get(pendingPdf))
        assertEquals("PDF pending bytes", pendingPdfFile.readText())
        assertEquals(copy.copy(sourceAvailability = SourceAvailability.UNCONFIRMED), state.find(copy.key))
        assertEquals("EPUB bytes", copyFile(copy).readText())
        database.close()
        reopen()
        assertNull(metadata.currentImport())
        assertNotNull(state.find(copy.key))
        assertEquals(pendingPdfEntry, queue.get(pendingPdf))
        assertEquals("PDF pending bytes", pendingPdfFile.readText())
        // An explicit import remains available after clearing: cleanup is not a permanent ban.
        val source = CalibreFixture.create(File(root, "reload.db"), libraryUuid = requireNotNull(imported.metadata.sourceLibraryUuid), bookUuid = book.sourceUuid)
        assertNotNull(metadata.importSnapshot(state.current()!!.token, source))
        assertEquals(column, metadata.currentImport()!!.selectedReadColumn)
    }

    @Test
    fun otherLibraryCleanupUsesPrivateIdentityAndKeepsCurrentLibraryAndProtection() = runBlocking<Unit> {
        val old = book
        val oldCopy = publish(old, "EPUB")
        val oldCover = cover(old)
        val oldTask = pendingCopy(oldCopy.key)
        val write = protectedWrite(old)
        val writeEntry = queue.get(write)
        val protected = File(files, "write-recovery/${UUID.randomUUID()}/backup.db").apply {
            parentFile!!.mkdirs(); writeText("old library protection")
        }
        book = activate("second")
        val currentCopy = publish(book, "PDF")
        val currentCover = cover(book)
        val selected = state.current()
        val currentImport = metadata.currentImport()
        val plan = requireNotNull(maintenance.previewOtherLibraries())
        assertEquals(setOf(old.libraryId), plan.libraries)
        assertTrue(maintenance.execute(plan))
        assertNull(state.find(oldCopy.key))
        assertFalse(copyFile(oldCopy).exists())
        assertFalse(oldCover.exists())
        assertCancelled(oldTask)
        assertFalse(staging(oldTask).exists())
        assertEquals(currentCopy, state.find(currentCopy.key))
        assertTrue(currentCover.isFile)
        assertEquals(currentImport, metadata.currentImport())
        assertEquals(selected, state.current())
        assertEquals(writeEntry, queue.get(write))
        assertEquals("old library protection", protected.readText())
    }

    @Test
    fun obsoleteSelectionRejectsConfirmationWithoutDeletingEitherLibrary() = runBlocking<Unit> {
        val old = publish(book, "EPUB")
        val plan = requireNotNull(maintenance.previewCopies(setOf(book)))
        book = activate("second")
        val current = publish(book, "EPUB")
        assertFalse(maintenance.execute(plan))
        assertEquals(old, state.find(old.key))
        assertEquals(current, state.find(current.key))
        assertTrue(copyFile(old).exists())
        assertTrue(copyFile(current).exists())
    }

    @Test
    fun cleanupWaitsForExecutorBoundaryThenPreventsOldPublication() = runBlocking<Unit> {
        val copy = publish(book, "EPUB")
        val task = pendingCopy(copy.key)
        val plan = requireNotNull(maintenance.previewCopies(setOf(book)))
        queue.executionLock.lock()
        val started = CompletableDeferred<Unit>()
        val cleanup = async(Dispatchers.Default) { started.complete(Unit); maintenance.execute(plan) }
        try {
            started.await()
            withTimeout(10_000) { while (queue.get(task)!!.control != TaskControl.CANCEL) delay(10) }
            assertTrue(copyFile(copy).isFile)
            assertNull(state.find(copy.key))
            assertFalse(cleanup.isCompleted)
        } finally { queue.executionLock.unlock() }
        assertTrue(withTimeout(10_000) { cleanup.await() })
        assertCancelled(task)
        val stale = copy.copy(location = CompleteCopyLocation(book.libraryId, UUID.randomUUID()))
        copyFile(stale).apply { parentFile!!.mkdirs(); writeText("EPUB bytes") }
        assertFalse(state.publishComplete(stale, task.value))
        assertNull(state.find(copy.key))
    }

    @Test
    fun interruptedDeletionPersistsJournalAndResumesAfterDatabaseReopen() = runBlocking<Unit> {
        val copy = publish(book, "EPUB")
        val image = cover(book)
        val plan = requireNotNull(maintenance.previewMetadata())
        val protected = File(root, "protected-backup.db").apply { writeText("recovery bytes") }
        val link = File(image.parentFile, "unexpected-link")
        Files.createSymbolicLink(link.toPath(), protected.toPath())
        assertFalse(maintenance.execute(plan))
        assertNull(metadata.currentImport())
        assertEquals(1L, count("cache_cleanup"))
        assertEquals("recovery bytes", protected.readText())
        assertNotNull(state.find(copy.key))
        database.close()
        reopen()
        assertEquals(1L, count("cache_cleanup"))
        assertTrue(link.delete())
        queue.executionLock.lock()
        try { maintenance.recoverLocked() } finally { queue.executionLock.unlock() }
        assertEquals(0L, count("cache_cleanup"))
        assertFalse(image.exists())
        assertEquals("recovery bytes", protected.readText())
        assertEquals("EPUB bytes", copyFile(copy).readText())
        assertNull(metadata.currentImport())
    }

    @Test
    fun linkedBookRootFailsCleanupAndJournalResumesWithoutFollowingTheLink() = runBlocking<Unit> {
        val copy = publish(book, "EPUB")
        val plan = requireNotNull(maintenance.previewCopies(setOf(book)))
        val books = File(files, "books")
        val heldBooks = File(root, "held-books")
        assertTrue(books.renameTo(heldBooks))
        Files.createSymbolicLink(books.toPath(), heldBooks.toPath())
        try {
            assertFalse(maintenance.execute(plan))
            assertEquals(1L, count("cache_cleanup"))
            assertNull(state.find(copy.key))
            val heldCopy = File(heldBooks, "${book.libraryId.value}/${copy.location.fileGeneration}.book")
            assertEquals("EPUB bytes", heldCopy.readText())
        } finally {
            Files.delete(books.toPath())
            assertTrue(heldBooks.renameTo(books))
        }
        database.close()
        reopen()
        queue.executionLock.lock()
        try { maintenance.recoverLocked() } finally { queue.executionLock.unlock() }
        assertEquals(0L, count("cache_cleanup"))
        assertFalse(copyFile(copy).exists())
        assertNull(state.find(copy.key))
    }

    @Test
    fun versionFourMigrationKeepsLibraryUuidAndReadColumnThroughMetadataCleanup() = runBlocking<Unit> {
        val imported = requireNotNull(metadata.currentImport())
        val column = CustomColumnId(1, "#finished")
        assertTrue(metadata.selectReadColumn(imported, column))
        StateSchemaHistory.downgrade(database.writableDatabase, 4)
        database.close()
        reopen()
        assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
        assertEquals(column, metadata.currentImport()!!.selectedReadColumn)
        state.setStartupEnabled(true)
        assertTrue(maintenance.execute(requireNotNull(maintenance.previewMetadata())))
        assertTrue(state.startupEnabled())
        val source = CalibreFixture.create(File(root, "migrated-reload.db"),
            libraryUuid = requireNotNull(imported.metadata.sourceLibraryUuid), bookUuid = book.sourceUuid)
        assertEquals(imported.identity, metadata.importSnapshot(state.current()!!.token, source))
        assertEquals(column, metadata.currentImport()!!.selectedReadColumn)
    }

    @Test
    fun runningProducerStopsAtBoundaryAndExplicitNewRequestCanPublishAfterCleanup() = runBlocking<Unit> {
        val copy = publish(book, "EPUB")
        val request = TaskRequest.FormatCopy(FormatResource(book, copy.key.format,
            SourceFileLocator.Relative(BackendKind.LOCAL, RelativeSourcePath("author/book/book.epub"))))
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        var stopCalls = 0
        val handler = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.FormatCopy
            override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(entry.stage, entry.checkpoint)
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                if (entry.stage == TaskStage.FORMAT_TRANSFER) {
                    arrived.complete(Unit)
                    release.await()
                    execution.checkControl()
                    return StageOutcome.Advance(TaskStage.FORMAT_PUBLISH)
                }
                execution.checkControl()
                val next = copy.copy(location = CompleteCopyLocation(book.libraryId, UUID.randomUUID()))
                copyFile(next).apply { parentFile!!.mkdirs(); writeText("EPUB bytes") }
                published = state.publishComplete(next, entry.record.id.value)
                return StageOutcome.Complete(cachePublished = published)
            }
            override suspend fun stopped(entry: QueueEntry) { stopCalls++ }
        }
        val driver = TaskCoordinator(queue, listOf(handler))
        val old = (driver.submit(TaskSubmission(request, TaskOrigin.USER_DOWNLOAD)) as SubmissionResult.Created).taskId
        val plan = requireNotNull(maintenance.previewCopies(setOf(book)))
        val drain = async(Dispatchers.Default) { driver.drain() }
        withTimeout(10_000) { arrived.await() }
        val cleanup = async(Dispatchers.Default) { maintenance.execute(plan) }
        try {
            withTimeout(10_000) { while (count("cache_cleanup") == 0L) delay(10) }
            assertFalse(cleanup.isCompleted)
        } finally { release.complete(Unit) }
        withTimeout(10_000) { drain.await() }
        assertTrue(withTimeout(10_000) { cleanup.await() })
        assertFalse(published)
        assertEquals(1, stopCalls)
        assertCancelled(old)
        assertNull(state.find(copy.key))
        val fresh = driver.submit(TaskSubmission(request, TaskOrigin.USER_DOWNLOAD)) as SubmissionResult.Created
        assertNotEquals(old, fresh.taskId)
        driver.drain()
        assertTrue(published)
        assertNotNull(state.find(copy.key))
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(fresh.taskId)!!.record.state)
    }

    @Test
    fun recoveryReclaimsUnboundObsoleteCandidateInputsWithoutTouchingPublishedOrProtectedData() = runBlocking<Unit> {
        val copy = publish(book, "EPUB")
        val imported = requireNotNull(metadata.currentImport())
        val selected = requireNotNull(state.current())
        val snapshot = File(files, "metadata/${imported.generation}/metadata.db")
        val protected = File(files, "write-recovery/${UUID.randomUUID()}/backup.db").apply {
            parentFile!!.mkdirs(); writeText("protected bytes")
        }
        // A listing for a library being added, abandoned with its addition.
        val candidate = requireNotNull(state.beginAddition(BackendKind.ONEDRIVE, UUID.randomUUID()).first.context)
        val old = submit(TaskRequest.CandidateConfiguration(candidate, "onedrive_browse"), TaskOrigin.MANUAL_SYNC)
        database.readableDatabase.rawQuery("SELECT scope_library_id FROM queued_tasks WHERE task_id = ?",
            arrayOf(old.value.toString())).use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
        val temporary = listOf(
            File(files, "snapshots/local/${old.value}/metadata.db"),
            File(files, "snapshots/onedrive/${old.value}/metadata.db"),
            File(files, "onedrive-browser/${old.value}.json"),
            File(files, "onedrive-browser/${old.value}.part"),
        ).onEach { it.parentFile!!.mkdirs(); it.writeText("obsolete candidate input") }
        state.cancelAddition()
        assertEquals(selected, state.current())
        queue.executionLock.lock()
        try { maintenance.recoverLocked() } finally { queue.executionLock.unlock() }
        assertCancelled(old)
        assertEquals(0L, count("cache_cleanup"))
        temporary.forEach { assertFalse(it.exists()) }
        assertFalse(File(files, "snapshots/local/${old.value}").exists())
        assertFalse(File(files, "snapshots/onedrive/${old.value}").exists())
        assertEquals(imported, metadata.currentImport())
        assertTrue(snapshot.isFile)
        assertEquals(copy, state.find(copy.key))
        assertEquals("EPUB bytes", copyFile(copy).readText())
        assertEquals("protected bytes", protected.readText())
    }

    @Test
    fun deletingAnotherLibraryClearsAllItsDataAndKeepsTheCurrentOne() = runBlocking<Unit> {
        val deleted = listedLibrary("deleted")
        val deletedCopy = publish(deleted, "EPUB")
        val deletedCover = cover(deleted)
        val deletedTask = pendingCopy(publish(deleted, "PDF").key)
        val args = arrayOf<Any>(deleted.libraryId.value.toString())
        database.writableDatabase.execSQL("INSERT INTO search_history(library_id, query, sequence) VALUES(?, '书名', 1)", args)
        database.writableDatabase.execSQL(
            "INSERT INTO last_opened(library_id, source_id, source_uuid, format, title) VALUES(?, 1, ?, 'EPUB', 'Fixture book')",
            args + deleted.sourceUuid.toString())
        val kept = listedLibrary("kept")
        val keptCopy = publish(kept, "EPUB")
        val current = state.current()
        val location = requireNotNull(state.binding(deleted.libraryId)).location

        val plan = requireNotNull(maintenance.previewLibrary(location))
        assertEquals(setOf(deleted.libraryId), plan.libraries)
        assertEquals(2, plan.copies.size)
        assertFalse(plan.deletesCurrent)
        assertTrue(plan.bytes >= requireNotNull(deletedCopy.sizeBytes))
        assertTrue(maintenance.execute(plan))

        assertEquals(listOf(requireNotNull(current?.location)), state.libraries().map { it.location })
        assertEquals(current, state.current())
        assertNull(state.find(deletedCopy.key))
        assertFalse(copyFile(deletedCopy).exists())
        assertFalse(deletedCover.exists())
        assertCancelled(deletedTask)
        for (table in listOf("search_history", "last_opened", "library_preferences", "metadata_imports", "cover_cache")) {
            database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE library_id = ?", arrayOf(deleted.libraryId.value.toString())).use {
                it.moveToFirst(); assertEquals(table, 0, it.getInt(0))
            }
        }
        assertEquals(keptCopy, state.find(keptCopy.key))
        assertEquals("EPUB bytes", copyFile(keptCopy).readText())
        assertNotNull(metadata.currentImport())
        assertNull(maintenance.previewLibrary(location))
    }

    @Test
    fun deletingTheCurrentLibraryLeavesNoCurrentLibraryAndKeepsProtectedWrites() = runBlocking<Unit> {
        val deleted = listedLibrary("current")
        val copy = publish(deleted, "EPUB")
        val protectedTask = protectedWrite(deleted)
        val protectedEntry = queue.get(protectedTask)
        val location = requireNotNull(state.current()!!.location)
        val stale = requireNotNull(maintenance.previewLibrary(location))
        assertTrue(stale.deletesCurrent)
        // A plan made under another selection is not executed.
        state.switchTo(location)
        assertFalse(maintenance.execute(stale))
        assertEquals(copy, state.find(copy.key))

        assertTrue(maintenance.execute(requireNotNull(maintenance.previewLibrary(location))))
        assertNull(state.current())
        assertTrue(state.libraries().isEmpty())
        assertNull(state.find(copy.key))
        assertEquals(protectedEntry, queue.get(protectedTask))
        // Other-library cleanup still works with no current library.
        assertNotNull(maintenance.previewOtherLibraries())
        assertNull(maintenance.previewMetadata())
    }

    @Test
    fun metadataCleanupRemovesEveryOwnedSnapshotGenerationAndPreservesOtherLibrarySnapshots() = runBlocking<Unit> {
        val currentIdentity = requireNotNull(state.current()!!.identity)
        val currentSource = File(root, "first.db")
        activate("other")
        val other = requireNotNull(metadata.currentImport())
        val otherSnapshot = File(files, "metadata/${other.generation}/metadata.db")
        state.select(currentIdentity.location)
        assertEquals(currentIdentity, metadata.importSnapshot(state.current()!!.token, currentSource))
        val first = requireNotNull(metadata.currentImport())
        val firstDirectory = File(files, "metadata/${first.generation}")
        val firstBytes = File(firstDirectory, "metadata.db").readBytes()
        assertEquals(currentIdentity, metadata.importSnapshot(state.current()!!.token, currentSource))
        val second = requireNotNull(metadata.currentImport())
        assertNotEquals(first.generation, second.generation)
        assertFalse(firstDirectory.exists())
        // Retained old bytes model an interrupted prior collection after successful publication.
        firstDirectory.mkdirs()
        File(firstDirectory, "metadata.db").writeBytes(firstBytes)
        File(firstDirectory, "library-id").writeText(currentIdentity.id.value.toString())
        val secondDirectory = File(files, "metadata/${second.generation}")
        assertTrue(maintenance.execute(requireNotNull(maintenance.previewMetadata())))
        assertFalse(firstDirectory.exists())
        assertFalse(secondDirectory.exists())
        assertNull(metadata.currentImport())
        assertTrue(otherSnapshot.isFile)
        state.select(other.identity.location)
        assertEquals(other, metadata.currentImport())
    }

    @Test
    fun otherLibraryCleanupWithoutCurrentLibraryIncludesAllOldBindingsAndKeepsProtection() = runBlocking<Unit> {
        val first = book
        val firstCopy = publish(first, "EPUB")
        val protectedTask = protectedWrite(first)
        val protectedEntry = queue.get(protectedTask)
        val second = activate("second")
        val secondCopy = publish(second, "PDF")
        val protected = File(files, "write-recovery/${UUID.randomUUID()}/backup.db").apply {
            parentFile!!.mkdirs(); writeText("unresolved source recovery")
        }
        val addition = state.beginAddition(BackendKind.ONEDRIVE, UUID.randomUUID()).first
        noCurrentLibrary()
        assertNull(maintenance.previewMetadata())
        val plan = requireNotNull(maintenance.previewOtherLibraries())
        assertEquals(setOf(first.libraryId, second.libraryId), plan.libraries)
        assertEquals(setOf(firstCopy.key, secondCopy.key), plan.copies)
        assertTrue(maintenance.execute(plan))
        assertNull(state.current())
        assertEquals(addition, state.addition())
        assertNull(state.find(firstCopy.key))
        assertNull(state.find(secondCopy.key))
        assertFalse(copyFile(firstCopy).exists())
        assertFalse(copyFile(secondCopy).exists())
        assertEquals(0L, count("metadata_imports"))
        assertEquals(0L, count("metadata_books"))
        assertEquals(protectedEntry, queue.get(protectedTask))
        assertEquals("unresolved source recovery", protected.readText())
    }

    @Test
    fun otherLibraryCleanupJournalAllowsListingsForTheLibraryBeingAdded() = runBlocking<Unit> {
        for (backend in listOf(BackendKind.LOCAL, BackendKind.ONEDRIVE)) {
            val first = activate("journal-first-${backend.name.lowercase()}")
            val firstCopy = publish(first, "EPUB")
            val second = activate("journal-second-${backend.name.lowercase()}")
            val secondCopy = publish(second, "PDF")
            val protectedTask = protectedWrite(first)
            val protectedEntry = queue.get(protectedTask)
            val protected = File(files, "write-recovery/${UUID.randomUUID()}/backup.db").apply {
                parentFile!!.mkdirs(); writeText("protected ${backend.name}")
            }
            val candidate = requireNotNull(state.beginAddition(backend, UUID.randomUUID()).first.context)
            noCurrentLibrary()
            val selected = state.current()
            val plan = requireNotNull(maintenance.previewOtherLibraries())
            assertTrue(first.libraryId in plan.libraries && second.libraryId in plan.libraries)
            queue.executionLock.lock()
            val cleanup = async(Dispatchers.Default) { maintenance.execute(plan) }
            var submitted: TaskId? = null
            try {
                withTimeout(10_000) { while (count("cache_cleanup") == 0L) delay(10) }
                assertFalse(cleanup.isCompleted)
                val result = queue.submit(TaskSubmission(TaskRequest.CandidateConfiguration(candidate, "onedrive_browse"),
                    TaskOrigin.MANUAL_SYNC))
                assertTrue("Listing for the addition was rejected for $backend", result is SubmissionResult.Created)
                submitted = (result as SubmissionResult.Created).taskId
                assertEquals(TaskState.Queued, queue.get(submitted)!!.record.state)
            } finally { queue.executionLock.unlock() }
            assertTrue(withTimeout(10_000) { cleanup.await() })
            val task = requireNotNull(submitted)
            assertEquals(TaskState.Queued, queue.get(task)!!.record.state)
            assertNull(queue.get(task)!!.control)
            database.readableDatabase.rawQuery("SELECT revoked FROM queued_tasks WHERE task_id = ?",
                arrayOf(task.value.toString())).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            assertEquals(selected, state.current())
            assertEquals(protectedEntry, queue.get(protectedTask))
            assertEquals("protected ${backend.name}", protected.readText())
            assertNull(state.find(firstCopy.key))
            assertNull(state.find(secondCopy.key))
            assertFalse(copyFile(firstCopy).exists())
            assertFalse(copyFile(secondCopy).exists())
        }
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, databaseName)
        bookFiles = PrivateBookFiles(files)
        state = ApplicationStateRepository(database, bookFiles, Dispatchers.IO)
        metadata = MetadataRepository(database, state, File(files, "metadata"), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        maintenance = CacheMaintenance(database, state, queue, files, Dispatchers.IO)
    }

    /** A library added through the list, made current and imported. */
    private suspend fun listedLibrary(name: String): BookKey {
        val selected = state.addLibrary(LibraryLocation.Local("test.documents", name), "content://test.documents/tree/$name")
        val uuid = UUID.randomUUID()
        val identity = requireNotNull(metadata.importSnapshot(selected.token, CalibreFixture.create(File(root, "$name.db"), bookUuid = uuid)))
        return BookKey(identity.id, 1, uuid)
    }

    private suspend fun activate(name: String): BookKey {
        val selected = state.select(LibraryLocation.Local("test.documents", name))
        val uuid = UUID.randomUUID()
        val source = CalibreFixture.create(File(root, "$name.db"), bookUuid = uuid)
        val identity = requireNotNull(metadata.importSnapshot(selected.token, source))
        return BookKey(identity.id, 1, uuid)
    }

    private suspend fun publish(key: BookKey, format: String): DownloadedCopy {
        val bytes = "$format bytes"
        val copy = DownloadedCopy(CopyKey(key, BookFormat.parse(format)),
            CompleteCopyLocation(key.libraryId, UUID.randomUUID()), "Fixture book", bytes.toByteArray().size.toLong(),
            FileVersion(BackendKind.LOCAL, "fixture-version"), SourceAvailability.AVAILABLE)
        copyFile(copy).apply { parentFile!!.mkdirs(); writeText(bytes) }
        assertTrue(state.publishComplete(copy))
        return copy
    }

    private suspend fun pendingCopy(key: CopyKey): TaskId {
        val task = submit(TaskRequest.FormatCopy(FormatResource(key.book, key.format,
            SourceFileLocator.Relative(BackendKind.LOCAL, RelativeSourcePath("author/book/file.${key.format.value.lowercase()}")))),
            TaskOrigin.USER_DOWNLOAD)
        val checkpoint = RecoveryCheckpoint(UUID.randomUUID(), FileVersion(BackendKind.LOCAL, "fixture-version"))
        queue.update(task) { it.copy(checkpoint = checkpoint, record = it.record.copy(state = TaskState.Paused(it.stage))) }
        File(staging(task), "${checkpoint.generation}.part").apply { parentFile!!.mkdirs(); writeText("partial") }
        File(staging(task), "${checkpoint.generation}.resume").writeText("private checkpoint evidence")
        return task
    }

    /** Fixture for the safe rename-before-manifest window of a format publication. */
    private suspend fun renamedCopy(task: TaskId, key: CopyKey): File {
        val checkpoint = requireNotNull(queue.get(task)!!.checkpoint)
        queue.update(task) { it.copy(stage = TaskStage.FORMAT_PUBLISH,
            record = it.record.copy(state = TaskState.Paused(TaskStage.FORMAT_PUBLISH))) }
        return File(files, "books/${key.book.libraryId.value}/${checkpoint.generation}.book").apply {
            parentFile!!.mkdirs(); writeText("${key.format.value} pending bytes")
        }
    }

    private suspend fun protectedWrite(key: BookKey): TaskId {
        val task = submit(TaskRequest.ReadStatusWrite(key.libraryId, FrozenSet(listOf(key)), CustomColumnId(1, "#finished"), true),
            TaskOrigin.USER_READ_STATUS)
        queue.update(task) { it.copy(stage = TaskStage.RECOVERY_CHECK,
            record = it.record.copy(commit = CommitState.Unknown(UUID.randomUUID()),
                state = TaskState.Waiting(FrozenSet(listOf(WaitingReason.RECOVERY))))) }
        return task
    }

    private fun cover(key: BookKey): File {
        val generation = UUID.randomUUID()
        val file = File(files, "covers/${key.libraryId.value}/$generation.png").apply {
            parentFile!!.mkdirs(); writeText("private image fixture")
        }
        database.writableDatabase.execSQL("INSERT INTO cover_cache(library_id,source_id,source_uuid,file_generation,size_bytes,last_access) VALUES(?,?,?,?,?,?)",
            arrayOf<Any>(key.libraryId.value.toString(), key.sourceId, key.sourceUuid.toString(), generation.toString(), file.length(), 1L))
        return file
    }

    private suspend fun submit(request: TaskRequest, origin: TaskOrigin) =
        (queue.submit(TaskSubmission(request, origin)) as SubmissionResult.Created).taskId

    private suspend fun assertCancelled(task: TaskId) {
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(task)!!.record.state)
        assertNull(queue.get(task)!!.checkpoint)
    }

    /** The state deleting the current library leaves: no current selection. */
    private fun noCurrentLibrary() {
        database.writableDatabase.delete("current_selection", null, null)
    }

    private fun count(table: String) = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    private fun copyFile(copy: DownloadedCopy) = File(files, "books/${copy.key.book.libraryId.value}/${copy.location.fileGeneration}.book")
    private fun staging(task: TaskId) = File(files, "book-staging/${task.value}")
}
