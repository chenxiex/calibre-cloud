package io.github.chenxiex.calibrecloud.tasks.copies

import io.github.chenxiex.calibrecloud.state.addLibrary
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import android.app.Application
import io.github.chenxiex.calibrecloud.ui.TaskViewModel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.CalibreFixture
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.SourcePolicies
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.cache.PrivateCopyReader
import io.github.chenxiex.calibrecloud.storage.local.*
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveLibrarySource
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceBackend
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Production transfer/publication and real SQLite; all source bytes are independent test fixtures. */
@RunWith(AndroidJUnit4::class)
class FormatCopyTaskHandlerTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var metadata: MetadataRepository
    private lateinit var files: PrivateBookFiles
    private lateinit var root: File
    private lateinit var book: BookKey
    private val libraries = mutableSetOf<LibraryId>()
    private val bookUuid = UUID.randomUUID()

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "format-copy-test-${UUID.randomUUID()}.db"
        root = File(context.cacheDir, "format-copy-test-${UUID.randomUUID()}").apply { mkdirs() }
        files = PrivateBookFiles(context.filesDir)
        reopen()
        book = activate("first")
    }

    @After
    fun tearDown() {
        runBlocking { queue.list().forEach { File(context.filesDir, "book-staging/${it.record.id.value}").deleteRecursively() } }
        database.close()
        context.deleteDatabase(databaseName)
        libraries.forEach { File(context.filesDir, "books/${it.value}").deleteRecursively() }
        root.deleteRecursively()
    }

    @Test
    fun completeCopySurvivesReopenAndOrdinaryReadMakesNoSourceRequest() = runBlocking<Unit> {
        val source = Source(epub("first"))
        assertEquals(CopyReadResult.Missing, PrivateCopyReader(state, files, Dispatchers.IO, state.copyAccess).read(key()))
        assertEquals(0, source.opens.get())
        val task = submit()
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        val published = requireNotNull(state.find(key()))
        assertEquals(SourceAvailability.AVAILABLE, published.sourceAvailability)
        assertArrayEquals(source.bytes, readBytes())
        val calls = source.opens.get()
        database.close()
        reopen()
        assertEquals(published, state.find(key()))
        assertArrayEquals(source.bytes, readBytes())
        assertEquals(calls, source.opens.get())
    }

    @Test
    fun successfulUpdateKeepsOpenOldHandleAndUsesNewGeneration() = runBlocking<Unit> {
        val first = Source(epub("old"))
        submit()
        coordinator(first).drain()
        val old = requireNotNull(state.find(key()))
        val oldFile = File(context.filesDir, "books/${old.location.libraryId.value}/${old.location.fileGeneration}.book")
        val handle = (PrivateCopyReader(state, files, Dispatchers.IO, state.copyAccess).read(key()) as CopyReadResult.Available).handle
        try {
            val second = Source(epub("new"))
            submit()
            coordinator(second).drain()
            val current = requireNotNull(state.find(key()))
            assertNotEquals(old.location, current.location)
            assertTrue(oldFile.isFile)
            assertArrayEquals(first.bytes, handle.input.readBytes())
            assertArrayEquals(second.bytes, readBytes())
        } finally { handle.close() }
        assertFalse(oldFile.exists())
    }

    @Test
    fun corruptContentVersionChangeAndSpaceFailureKeepCompleteOldCopy() = runBlocking<Unit> {
        val original = Source(epub("old"))
        submit()
        coordinator(original).drain()
        val old = requireNotNull(state.find(key()))
        val corrupt = submit()
        coordinator(Source("broken".toByteArray())).drain()
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, failedKind(corrupt))
        assertEquals(old, state.find(key()))
        val conflict = submit()
        coordinator(Source(epub("new")).apply { changeAfterOpen = true }).drain()
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failedKind(conflict))
        assertEquals(old, state.find(key()))
        val noSpace = submit()
        coordinator(Source(epub("new")), availableBytes = { 0L }).drain()
        assertEquals(StorageErrorKind.INSUFFICIENT_SPACE, failedKind(noSpace))
        assertEquals(old, state.find(key()))
        assertArrayEquals(original.bytes, readBytes())
    }

    @Test
    fun restartTransferEvidenceRoundTripsAndLegacyPayloadDefaultsFalse() = runBlocking<Unit> {
        val task = submit()
        val original = queue.get(task)!!.record
        val restarted = original.copy(restartedTransfer = true)
        assertEquals(restarted, TaskCodec.decode(TaskCodec.encode(restarted)))
        val legacy = JSONObject(TaskCodec.encode(restarted)).apply { remove("restarted_transfer") }
        assertEquals(original, TaskCodec.decode(legacy.toString()))
    }

    @Test
    fun pauseAndResumeReacquireVersionAndRestartWhenSourceChanges() = runBlocking<Unit> {
        val source = Source(epub("old")).apply { blockAfterChunk = true }
        val task = submit()
        val driver = launch(Dispatchers.Default) { coordinator(source).drain() }
        assertTrue(source.started.await(10, TimeUnit.SECONDS))
        assertNull(state.find(key()))
        assertEquals(CopyReadResult.Missing, PrivateCopyReader(state, files, Dispatchers.IO, state.copyAccess).read(key()))
        assertTrue(queue.control(task, TaskControl.PAUSE))
        source.release.countDown()
        withTimeout(10_000) { driver.join() }
        assertTrue(queue.get(task)!!.record.state is TaskState.Paused)
        assertNull(state.find(key()))
        assertEquals(64L, partialFile(task).length())
        source.bytes = epub("changed")
        source.token = hash(source.bytes)
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertTrue(queue.get(task)!!.record.restartedTransfer)
        assertArrayEquals(source.bytes, readBytes())
        assertEquals(2, source.opens.get())
        assertTrue(source.rangeOffsets.isEmpty())
    }

    @Test
    fun pausedTransferResumesAtDurableOffsetWithoutReadingPrefixAgain() = runBlocking<Unit> {
        val source = Source(epub("resume"))
        val task = pauseTransfer(source)
        assertEquals(64L, partialFile(task).length())
        assertNull(state.find(key()))
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertFalse(queue.get(task)!!.record.restartedTransfer)
        assertEquals(listOf(64L), source.rangeOffsets)
        assertEquals(1, source.opens.get())
        assertEquals(source.bytes.size.toLong(), source.bytesRead.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun processRecoveryResumesVerifiedPartialAtNonzeroOffset() = runBlocking<Unit> {
        val source = Source(epub("process resume"))
        val task = pauseTransfer(source)
        assertTrue(queue.control(task, TaskControl.RESUME))
        queue.update(task) { it.copy(record = it.record.copy(state = TaskState.Running(TaskStage.FORMAT_TRANSFER))) }
        database.close()
        reopen()
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertEquals(listOf(64L), source.rangeOffsets)
        assertEquals(1, source.opens.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun unconfirmedPartialTailIsTruncatedToDurableOffset() = runBlocking<Unit> {
        val source = Source(epub("unconfirmed tail"))
        val task = pauseTransfer(source)
        partialFile(task).appendBytes("unconfirmed crash tail".toByteArray())
        assertTrue(partialFile(task).length() > 64)
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertEquals(listOf(64L), source.rangeOffsets)
        assertEquals(1, source.opens.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun oneDriveVersionedPartialResumesAtOffsetAndChecksFinalLength() = runBlocking<Unit> {
        val selection = state.select(LibraryLocation.OneDrive("fixture-account", "fixture-drive", "fixture-root"))
        val identity = requireNotNull(metadata.importSnapshot(selection.token,
            CalibreFixture.create(File(root, "onedrive-resume.db"), bookUuid = bookUuid)))
        libraries.add(identity.id)
        val remoteBook = BookKey(identity.id, 1, bookUuid)
        val request = TaskRequest.FormatCopy(FormatResource(remoteBook, BookFormat.parse("EPUB"),
            SourceFileLocator.Relative(BackendKind.ONEDRIVE, RelativeSourcePath("作者/书名 (1)/正文.epub"))))
        val source = Source(epub("OneDrive resume"), "fixture-cTag").apply { knownSize = bytes.size.toLong() }
        val task = pauseTransfer(source, request)
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertEquals(listOf(64L), source.rangeOffsets)
        assertEquals(1, source.opens.get())
        assertEquals(source.bytes.size.toLong(), source.bytesRead.get())
        assertArrayEquals(source.bytes, readBytes(key(target = remoteBook)))
        assertEquals(FileVersion(BackendKind.ONEDRIVE, "fixture-cTag"), state.find(key(target = remoteBook))!!.savedVersion)
    }

    @Test
    fun tamperedPartialIsDiscardedBeforeResuming() = runBlocking<Unit> {
        val source = Source(epub("tamper"))
        val task = pauseTransfer(source)
        val partial = partialFile(task)
        val contents = partial.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        partial.writeBytes(contents)
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertTrue(queue.get(task)!!.record.restartedTransfer)
        assertTrue(source.rangeOffsets.isEmpty())
        assertEquals(2, source.opens.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun corruptResumeEvidenceRestartsWithoutCombiningPartialBytes() = runBlocking<Unit> {
        val source = Source(epub("sidecar"))
        val task = pauseTransfer(source)
        val evidence = File(context.filesDir, "book-staging/${task.value}").listFiles().orEmpty()
            .filter { it.isFile && it.extension != "part" }
        assertTrue("A durable offset/hash sidecar is required", evidence.isNotEmpty())
        evidence.forEach { it.writeText("corrupt evidence") }
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertTrue(queue.get(task)!!.record.restartedTransfer)
        assertTrue(source.rangeOffsets.isEmpty())
        assertEquals(2, source.opens.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun unsupportedRangeRestartsAndPublishesOnlyWholeSource() = runBlocking<Unit> {
        val source = Source(epub("range unavailable"))
        val task = pauseTransfer(source)
        source.rangeSupported = false
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertTrue(queue.get(task)!!.record.restartedTransfer)
        assertEquals(listOf(64L), source.rangeOffsets)
        assertEquals(2, source.opens.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun changedFrozenVersionRejectsResumeAndKeepsPreviousCompleteCopy() = runBlocking<Unit> {
        val source = Source(epub("old complete"))
        submit()
        coordinator(source).drain()
        val old = requireNotNull(state.find(key()))
        source.bytes = epub("queued update")
        source.token = hash(source.bytes)
        val task = pauseTransfer(source, request(version = FileVersion(BackendKind.LOCAL, source.token)))
        source.bytes = epub("changed again")
        source.token = hash(source.bytes)
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failedKind(task))
        assertEquals(old, state.find(key()))
        assertTrue(source.rangeOffsets.isEmpty())
        assertTrue(File(context.filesDir, "book-staging/${task.value}").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun transientReadInterruptionRetainsFsyncedOffsetForRetry() = runBlocking<Unit> {
        val source = Source(epub("network retry")).apply { networkAfterChunk = true }
        val task = submit()
        coordinator(source).drain()
        assertTrue(queue.get(task)!!.record.state is TaskState.Waiting)
        assertEquals(64L, partialFile(task).length())
        assertNull(state.find(key()))
        source.networkAfterChunk = false
        queue.update(task) { it.copy(retryAt = 0) }
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertEquals(listOf(64L), source.rangeOffsets)
        assertEquals(1, source.opens.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun unavailableSourceVersionPreservesPausedPrefixUntilNetworkOrAuthorizationRecovers() = runBlocking<Unit> {
        for (failure in listOf(StorageErrorKind.NO_NETWORK, StorageErrorKind.AUTHORIZATION_EXPIRED)) {
            val source = Source(epub("version unavailable $failure"))
            val previous = state.find(key())
            val task = pauseTransfer(source)
            source.failure = failure
            assertTrue(queue.control(task, TaskControl.RESUME))
            coordinator(source).drain()
            assertEquals(64L, partialFile(task).length())
            assertTrue(source.rangeOffsets.isEmpty())
            assertEquals(previous, state.find(key()))
            source.failure = null
            if (queue.get(task)!!.record.state is TaskState.Finished)
                assertTrue(queue.control(task, TaskControl.RETRY))
            else queue.update(task) { it.copy(retryAt = 0) }
            coordinator(source).drain()
            assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
            assertEquals(listOf(64L), source.rangeOffsets)
            assertEquals(1, source.opens.get())
            assertArrayEquals(source.bytes, readBytes())
        }
    }

    @Test
    fun unavailableRangeRequestPreservesPausedPrefixForNextRetry() = runBlocking<Unit> {
        val source = Source(epub("range request unavailable"))
        val task = pauseTransfer(source)
        source.rangeFailure = StorageErrorKind.NO_NETWORK
        assertTrue(queue.control(task, TaskControl.RESUME))
        coordinator(source).drain()
        assertTrue(queue.get(task)!!.record.state is TaskState.Waiting)
        assertEquals(64L, partialFile(task).length())
        assertEquals(listOf(64L), source.rangeOffsets)
        assertNull(state.find(key()))
        source.rangeFailure = null
        queue.update(task) { it.copy(retryAt = 0) }
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertEquals(listOf(64L, 64L), source.rangeOffsets)
        assertEquals(1, source.opens.get())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun cancelledPausedTransferDeletesStagingAndKeepsOldPublishedCopy() = runBlocking<Unit> {
        val original = Source(epub("old complete"))
        submit()
        coordinator(original).drain()
        val old = requireNotNull(state.find(key()))
        val source = Source(epub("cancelled replacement"))
        val task = pauseTransfer(source)
        assertTrue(queue.control(task, TaskControl.CANCEL))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(task)!!.record.state)
        assertTrue(File(context.filesDir, "book-staging/${task.value}").listFiles().orEmpty().isEmpty())
        assertEquals(old, state.find(key()))
        assertArrayEquals(original.bytes, readBytes())
    }

    @Test
    fun completedTransferRecoveryPublishesWithoutDownloadingAgain() = runBlocking<Unit> {
        val source = Source(epub("publish recovery"))
        val task = submit()
        val handler = FormatCopyTaskHandler(state, metadata, queue, source.sources, context.filesDir, Dispatchers.IO, { Long.MAX_VALUE })
        val interrupted = object : TaskHandler by handler {
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome =
                if (entry.stage == TaskStage.FORMAT_PUBLISH) StageOutcome.Wait(WaitingReason.NETWORK)
                else handler.execute(entry, execution)
        }
        TaskCoordinator(queue, listOf(interrupted)).drain()
        assertEquals(TaskStage.FORMAT_PUBLISH, queue.get(task)!!.stage)
        assertNull(state.find(key()))
        assertEquals(source.bytes.size.toLong(), partialFile(task).length())
        database.close()
        reopen()
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertEquals(1, source.opens.get())
        assertTrue(source.rangeOffsets.isEmpty())
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun cancelWhileReadingKeepsOldCopyAndClosesSource() = runBlocking<Unit> {
        val first = Source(epub("old"))
        submit()
        coordinator(first).drain()
        val old = requireNotNull(state.find(key()))
        val replacement = Source(epub("new")).apply { block = true }
        val task = submit()
        val driver = launch(Dispatchers.Default) { coordinator(replacement).drain() }
        assertTrue(replacement.started.await(10, TimeUnit.SECONDS))
        assertTrue(queue.control(task, TaskControl.CANCEL))
        replacement.release.countDown()
        withTimeout(10_000) { driver.join() }
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(task)!!.record.state)
        assertEquals(old, state.find(key()))
        assertArrayEquals(first.bytes, readBytes())
        assertTrue(replacement.closes.get() > 0)
        assertTrue(File(context.filesDir, "book-staging/${task.value}").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun formatAndLibraryIdentityKeepCopiesSeparate() = runBlocking<Unit> {
        val firstBook = book
        val epub = Source(epub("first"))
        submit()
        coordinator(epub).drain()
        val pdf = Source("%PDF-1.4\n1 0 obj <<>> endobj\n%%EOF\n".toByteArray())
        submit(request("PDF"))
        coordinator(pdf).drain()
        assertEquals(2, state.listCopies(book.libraryId, 10, 0).size)
        assertArrayEquals(epub.bytes, readBytes(key("EPUB")))
        assertArrayEquals(pdf.bytes, readBytes(key("PDF")))
        book = activate("second")
        assertEquals(firstBook.sourceId, book.sourceId)
        assertEquals(firstBook.sourceUuid, book.sourceUuid)
        assertNotEquals(firstBook.libraryId, book.libraryId)
        assertEquals(CopyReadResult.Missing, PrivateCopyReader(state, files, Dispatchers.IO, state.copyAccess).read(key()))
        val second = Source(epub("second"))
        submit()
        coordinator(second).drain()
        assertArrayEquals(second.bytes, readBytes())
        assertArrayEquals(epub.bytes, readBytes(key("EPUB", firstBook)))
    }

    @Test
    fun checksOnlyScheduleChangedDownloadedFormatAndPromoteUserRequest() = runBlocking<Unit> {
        val source = Source(epub("old"))
        submit()
        coordinator(source).drain()
        val unchanged = submitCheck(key())
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(unchanged)!!.record.state)
        val opens = source.opens.get()
        submitCheck(key("PDF"))
        coordinator(source).drain()
        assertEquals(opens, source.opens.get())
        assertNull(state.find(key("PDF")))
        val newBytes = epub("new")
        val expected = FileVersion(BackendKind.LOCAL, hash(newBytes))
        val automatic = submit(request(version = expected), TaskOrigin.DOWNLOADED_FORMAT_UPDATE)
        val promoted = queue.submit(TaskSubmission(request(version = expected), TaskOrigin.USER_DOWNLOAD))
        assertTrue(promoted is SubmissionResult.Promoted)
        assertEquals(automatic, (promoted as SubmissionResult.Promoted).taskId)
        assertEquals(TaskPriority.HIGH, queue.get(automatic)!!.record.scheduling.priority)
        source.bytes = newBytes
        source.token = hash(newBytes)
        coordinator(source).drain()
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun confirmedMissingKeepsBytesWhileLoginFailureDoesNotMarkMissing() = runBlocking<Unit> {
        val source = Source(epub("old"))
        submit()
        coordinator(source).drain()
        val copy = requireNotNull(state.find(key()))
        val loginCheck = submitCheck(key())
        coordinator(source.apply { failure = StorageErrorKind.LOGIN_REQUIRED }).drain()
        assertEquals(copy, state.find(key()))
        if (queue.get(loginCheck)!!.record.state !is TaskState.Finished) assertTrue(queue.control(loginCheck, TaskControl.CANCEL))
        submitCheck(key())
        coordinator(source.apply { failure = StorageErrorKind.SOURCE_MISSING }).drain()
        assertEquals(SourceAvailability.CONFIRMED_MISSING, state.find(key())!!.sourceAvailability)
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun processRecoveryRejectsStaleCheckpointAndReacquiresWholeSource() = runBlocking<Unit> {
        val task = submit()
        assertEquals(task, queue.claim(0, { emptySet() }, { true })!!.record.id)
        queue.update(task) { it.copy(checkpoint = RecoveryCheckpoint(UUID.randomUUID(), FileVersion(BackendKind.LOCAL, "old"))) }
        database.close()
        reopen()
        val source = Source(epub("fresh"))
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertTrue(queue.get(task)!!.record.restartedTransfer)
        assertNull(queue.get(task)!!.checkpoint)
        assertArrayEquals(source.bytes, readBytes())
        assertTrue(source.opens.get() > 0)
    }

    @Test
    fun frozenVersionMismatchRejectsTransferAndLeavesManifestEmpty() = runBlocking<Unit> {
        val task = submit(request(version = FileVersion(BackendKind.LOCAL, "previous")))
        val source = Source(epub("current"), "current")
        coordinator(source).drain()
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failedKind(task))
        assertNull(state.find(key()))
        assertEquals(0, source.opens.get())
    }

    @Test
    fun changedSourceCheckEnqueuesOnlyDownloadedFormatAtLowPriority() = runBlocking<Unit> {
        val source = Source(epub("old"))
        submit()
        coordinator(source).drain()
        source.bytes = epub("changed")
        source.token = hash(source.bytes)
        submitCheck(key())
        coordinator(source).drain()
        val updates = queue.list().filter { it.record.originalOrigin == TaskOrigin.DOWNLOADED_FORMAT_UPDATE &&
            it.record.submission.request is TaskRequest.FormatCopy }
        assertEquals(1, updates.size)
        assertEquals(TaskPriority.LOW, updates.single().record.scheduling.priority)
        assertEquals(key(), (updates.single().record.submission.request as TaskRequest.FormatCopy).resource.let { CopyKey(it.book, it.format) })
        assertArrayEquals(source.bytes, readBytes())
        assertNull(state.find(key("PDF")))
    }

    @Test
    fun copyServiceFreezesImportedPathAndRejectsStaleSelectionWithoutSourceAccess() = runBlocking<Unit> {
        val source = Source(epub("copy"))
        val service = CopyService(state, metadata, coordinator(source), queue)
        val selected = requireNotNull(state.current())
        val submitted = service.submit(key(), selected.token)
        assertTrue(submitted is SubmissionResult.Created)
        val request = queue.get((submitted as SubmissionResult.Created).taskId)!!.record.submission.request as TaskRequest.FormatCopy
        assertEquals(SourceFileLocator.Relative(BackendKind.LOCAL, RelativeSourcePath("作者/书名 (1)/正文.epub")), request.resource.source)
        assertEquals(0, source.opens.get())
        book = activate("second")
        assertTrue(service.submit(key(target = selected.identity!!.let { BookKey(it.id, 1, bookUuid) }), selected.token) is SubmissionResult.Rejected)
        assertEquals(0, source.opens.get())
    }

    @Test
    fun metadataImportPersistsChecksForDownloadedFormatsOnly() = runBlocking<Unit> {
        val source = Source(epub("copy"))
        submit()
        coordinator(source).drain()
        val previous = requireNotNull(metadata.currentImport())
        val refreshed = CalibreFixture.create(File(root, "refreshed.db"),
            libraryUuid = requireNotNull(previous.metadata.sourceLibraryUuid), bookUuid = bookUuid, boolValue = false)
        assertEquals(previous.identity, metadata.importSnapshot(state.current()!!.token, refreshed))
        val checks = queue.list().filter { it.record.submission.request is TaskRequest.FormatCheck }
        assertEquals(1, checks.size)
        assertEquals(key(), (checks.single().record.submission.request as TaskRequest.FormatCheck).key)
        assertEquals(TaskPriority.LOW, checks.single().record.scheduling.priority)
        database.close()
        reopen()
        assertTrue(queue.list().any { it.record.id == checks.single().record.id })
        assertNull(state.find(key("PDF")))
    }

    @Test
    fun oneDriveTaggedTransferVerifiesKnownLengthAndPersistsIndependentCopy() = runBlocking<Unit> {
        val selection = state.select(LibraryLocation.OneDrive("fixture-account", "fixture-drive", "fixture-root"))
        val identity = requireNotNull(metadata.importSnapshot(selection.token,
            CalibreFixture.create(File(root, "onedrive.db"), bookUuid = bookUuid)))
        libraries.add(identity.id)
        val oneDriveBook = BookKey(identity.id, 1, bookUuid)
        val request = TaskRequest.FormatCopy(FormatResource(oneDriveBook, BookFormat.parse("EPUB"),
            SourceFileLocator.Relative(BackendKind.ONEDRIVE, RelativeSourcePath("作者/书名 (1)/正文.epub"))))
        val source = Source(epub("OneDrive"), "fixture-cTag").apply { knownSize = bytes.size.toLong() }
        val task = submit(request)
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertArrayEquals(source.bytes, readBytes(key(target = oneDriveBook)))
        val old = state.find(key(target = oneDriveBook))
        source.knownSize = source.bytes.size.toLong() + 1
        val mismatch = submit(request)
        coordinator(source).drain()
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, failedKind(mismatch))
        assertEquals(old, state.find(key(target = oneDriveBook)))
    }

    @Test
    fun transientTransportFailurePreservesSourceAvailabilityAndCompleteBytes() = runBlocking<Unit> {
        val source = Source(epub("old"))
        submit()
        coordinator(source).drain()
        val old = state.find(key())
        val task = submit()
        coordinator(source.apply { failure = StorageErrorKind.NO_NETWORK }).drain()
        assertEquals(old, state.find(key()))
        assertArrayEquals(source.bytes, readBytes())
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), queue.get(task)!!.record.state)
    }

    @Test
    fun localAuthorizationLossWaitsForDirectoryAndContinuesAfterReauthorization() = runBlocking<Unit> {
        val source = Source(epub("waiting")).apply { failure = StorageErrorKind.AUTHORIZATION_EXPIRED }
        val task = submit()
        coordinator(source).drain()
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.DIRECTORY_AUTHORIZATION))), queue.get(task)!!.record.state)
        assertNull(state.find(key()))
        source.failure = null
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun estimatedSourceSizeDrivesSpacePrecheckAndProgressButNotIntegrity() = runBlocking<Unit> {
        val reserve = 1024L * 1024
        val tooSmall = Source(epub("estimated")).apply { estimate = bytes.size.toLong() }
        val refused = submit()
        coordinator(tooSmall, availableBytes = { reserve + tooSmall.bytes.size - 1 }).drain()
        assertEquals(StorageErrorKind.INSUFFICIENT_SPACE, failedKind(refused))
        assertEquals(0, tooSmall.opens.get())
        val totals = java.util.Collections.synchronizedList(mutableListOf<Long?>())
        val collector = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            queue.events.collect { event ->
                ((event as? TaskEvent.Changed)?.record?.state as? TaskState.Running)?.progress?.let { totals.add(it.total) }
            }
        }
        val source = Source(epub("estimated")).apply { estimate = bytes.size.toLong() }
        val task = submit()
        coordinator(source).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertTrue(totals.contains(source.bytes.size.toLong()))
        // An inaccurate provider size is only a hint; the content hash still decides integrity.
        val inaccurate = Source(epub("inaccurate")).apply { estimate = 10L }
        val second = submit()
        coordinator(inaccurate).drain()
        collector.cancel()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(second)!!.record.state)
        assertArrayEquals(inaccurate.bytes, readBytes())
    }

    @Test
    fun sourceStreamFailureAfterPartialReadClosesStreamAndKeepsOldBytes() = runBlocking<Unit> {
        val original = Source(epub("old"))
        submit()
        coordinator(original).drain()
        val old = requireNotNull(state.find(key()))
        val broken = Source(epub("new")).apply { failAfterFirstChunk = true }
        val task = submit()
        coordinator(broken).drain()
        assertEquals(StorageErrorKind.LOCAL_IO, failedKind(task))
        assertEquals(old, state.find(key()))
        assertArrayEquals(original.bytes, readBytes())
        assertTrue(broken.closes.get() > 0)
        assertEquals(64L, partialFile(task).length())
        assertNotNull(queue.get(task)!!.checkpoint)
    }

    @Test
    fun localBackendBridgeReadsIndependentFilesAndPublishesCompleteCopy() = runBlocking<Unit> {
        val bytes = epub("local backend")
        val sourceRoot = File(root, "local-library").apply { mkdirs() }
        val sourceFile = File(sourceRoot, "作者/书名 (1)/正文.epub").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val reads = AtomicInteger()
        val documents = object : LocalDocumentAccess {
            override fun root(treeUri: String) = LocalDocument("root", "library", true, false)
            override fun children(treeUri: String, parentId: String): List<LocalDocument> = when (parentId) {
                "root" -> listOf(LocalDocument("author", "作者", true, false))
                "author" -> listOf(LocalDocument("book", "书名 (1)", true, false))
                "book" -> listOf(LocalDocument("epub", "正文.epub", false, false))
                else -> emptyList()
            }
            override fun isWithinRoot(treeUri: String, documentId: String) = documentId in setOf("root", "author", "book", "epub")
            override fun openRead(treeUri: String, documentId: String): InputStream {
                check(documentId == "epub")
                reads.incrementAndGet()
                return sourceFile.inputStream()
            }
        }
        state.addLibrary(LibraryLocation.Local("test.documents", "first"), "content://test.documents/tree/first")
        assertEquals(book.libraryId, state.current()!!.identity!!.id)
        val local = LocalSourceBackend(documents, File(root, "local-snapshots"), SnapshotValidator { false }, Dispatchers.IO)
        val remote = OneDriveSourceBackend({ null }, File(root, "remote-snapshots"), SnapshotValidator { false }, Dispatchers.IO)
        val task = submit()
        coordinator(productionSources(local, remote)).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertTrue(reads.get() >= 3)
        assertArrayEquals(bytes, readBytes())
        assertArrayEquals(bytes, sourceFile.readBytes())
        assertEquals(FileVersion(BackendKind.LOCAL, hash(bytes)), state.find(key())!!.savedVersion)
    }

    @Test
    fun oneDriveBackendBridgeDownloadsWithOnePathRequestAndNoTokenOnContent() = runBlocking<Unit> {
        val bytes = epub("OneDrive backend")
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            when {
                request.url.host == "download.example" -> builder.code(200).body(bytes.toResponseBody()).build()
                request.url.encodedPath.startsWith("/v1.0/drives/drive/items/graph-library:/") -> builder.code(200).body(JSONObject(mapOf(
                    "id" to "epub", "name" to "正文.epub", "file" to emptyMap<String, Any>(),
                    "parentReference" to mapOf("id" to "book", "driveId" to "drive"), "cTag" to "content-1",
                    "size" to bytes.size.toLong(), "@microsoft.graph.downloadUrl" to "https://download.example/fixture?secret=ephemeral",
                )).toString().toResponseBody()).build()
                else -> error("Unexpected request")
            }
        }.build()
        val remoteBook = activateOneDrive("graph-library")
        val local = LocalSourceBackend(object : LocalDocumentAccess {
            override fun root(treeUri: String): LocalDocument = error("Unexpected local source")
            override fun children(treeUri: String, parentId: String): List<LocalDocument> = error("Unexpected local source")
            override fun isWithinRoot(treeUri: String, documentId: String): Boolean = error("Unexpected local source")
            override fun openRead(treeUri: String, documentId: String): InputStream = error("Unexpected local source")
        }, File(root, "unused-snapshots"), SnapshotValidator { false }, Dispatchers.IO)
        val remote = OneDriveSourceBackend({ "fixture-token" }, File(root, "graph-snapshots"), SnapshotValidator { false },
            Dispatchers.IO, client = client, contentClient = client, accountProvider = { "subject" })
        val task = submit(oneDriveRequest(remoteBook))
        coordinator(productionSources(local, remote)).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertArrayEquals(bytes, readBytes(key(target = remoteBook)))
        val graph = requests.filter { it.url.host == "graph.microsoft.com" }
        assertEquals(listOf("/v1.0/drives/drive/items/graph-library:/%E4%BD%9C%E8%80%85/%E4%B9%A6%E5%90%8D%20%281%29/%E6%AD%A3%E6%96%87.epub"),
            graph.map { it.url.encodedPath })
        assertTrue(graph.all { it.header("Authorization") == "Bearer fixture-token" })
        assertNull(requests.single { it.url.host == "download.example" }.header("Authorization"))
        assertEquals(FileVersion(BackendKind.ONEDRIVE, "content-1"), state.find(key(target = remoteBook))!!.savedVersion)
    }

    @Test
    fun oneDriveImportChecksOnlyCopiesWhoseCalibreRecordChanged() = runBlocking<Unit> {
        val remoteBook = activateOneDrive("stamped")
        val source = Source(epub("stamped"))
        submit(oneDriveRequest(remoteBook))
        coordinator(source).drain()
        val copy = key(target = remoteBook)
        assertNotNull(state.find(copy))
        reimport("stamped")
        assertEquals(emptyList<TaskRequest>(), pendingChecks())
        reimport("stamped", lastModified = "2026-03-04 05:06:07.000000+00:00")
        assertEquals(listOf(TaskRequest.FormatCheck(copy)), pendingChecks())
        source.paths.clear()
        coordinator(source).drain()
        assertEquals(1, source.paths.size)
        // The unchanged version was confirmed against the new record, so the same import is not checked again.
        reimport("stamped", lastModified = "2026-03-04 05:06:07.000000+00:00")
        assertEquals(emptyList<TaskRequest>(), pendingChecks())
        reimport("stamped", lastModified = "2026-03-04 05:06:07.000000+00:00", epubSize = 43)
        assertEquals(listOf(TaskRequest.FormatCheck(copy)), pendingChecks())
        coordinator(source).drain()
        // A copy downloaded before Calibre records were kept is checked once, then recorded.
        database.writableDatabase.execSQL("UPDATE downloaded_copies SET calibre_recorded = 0")
        reimport("stamped", lastModified = "2026-03-04 05:06:07.000000+00:00", epubSize = 43)
        assertEquals(listOf(TaskRequest.FormatCheck(copy)), pendingChecks())
        coordinator(source).drain()
        reimport("stamped", lastModified = "2026-03-04 05:06:07.000000+00:00", epubSize = 43)
        assertEquals(emptyList<TaskRequest>(), pendingChecks())
    }

    @Test
    fun staleOneDrivePathSyncsOnceAtInheritedPriorityAndRetriesAtNewPath() = runBlocking<Unit> {
        val remoteBook = activateOneDrive("renamed")
        val source = Source(epub("renamed")).apply { missing = setOf("作者/书名 (1)/正文.epub") }
        val sync = FixtureSync("renamed", "作者/新书名 (1)")
        val task = submit(oneDriveRequest(remoteBook))
        coordinator(source, sync).drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertEquals(listOf("作者/书名 (1)/正文.epub", "作者/新书名 (1)/正文.epub"), source.paths.distinct())
        assertEquals(listOf(TaskOrigin.USER_DOWNLOAD), sync.origins)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(sync.tasks.single())!!.record.state)
        assertArrayEquals(source.bytes, readBytes(key(target = remoteBook)))
        assertFalse(queue.list().any { it.record.submission.request is TaskRequest.CandidateConfiguration &&
            it.record.state !is TaskState.Finished })
    }

    @Test
    fun stillMissingAfterSyncFailsOnceAndOnlyThenMarksCopyUnavailable() = runBlocking<Unit> {
        val remoteBook = activateOneDrive("vanished")
        val source = Source(epub("vanished"))
        submit(oneDriveRequest(remoteBook))
        coordinator(source).drain()
        val copy = key(target = remoteBook)
        source.missing = setOf("作者/书名 (1)/正文.epub")
        var firstSync = true
        val sync = FixtureSync("vanished", "作者/书名 (1)") {
            if (firstSync) assertEquals(SourceAvailability.AVAILABLE, state.find(copy)!!.sourceAvailability)
            firstSync = false
        }
        source.paths.clear()
        val check = submitCheck(copy)
        coordinator(source, sync).drain()
        assertEquals(StorageErrorKind.SOURCE_MISSING, failedKind(check))
        // The sync kept the same path, so the retry fails without requesting it again.
        assertEquals(listOf("作者/书名 (1)/正文.epub"), source.paths)
        assertEquals(1, sync.tasks.size)
        assertEquals(listOf(TaskOrigin.DOWNLOADED_FORMAT_UPDATE), sync.origins)
        assertEquals(SourceAvailability.CONFIRMED_MISSING, state.find(copy)!!.sourceAvailability)
        assertTrue(queue.control(check, TaskControl.RETRY))
        // Retry drops the edge to the old sync, so the task can request a new one.
        assertNull(queue.get(check)!!.sourceSync)
        coordinator(source, sync).drain()
        assertEquals(2, sync.tasks.size)
    }

    @Test
    fun throttledOneDriveLibraryHoldsItsSourceTasksAcrossRestartButNotOtherLibraries() = runBlocking<Unit> {
        val remoteBook = activateOneDrive("throttled")
        val source = Source(epub("throttled")).apply { throttleMillis = 60_000 }
        var now = 1_000_000L
        fun throttledCoordinator() = TaskCoordinator(queue, listOf(FormatCopyTaskHandler(state, metadata, queue, source.sources,
            context.filesDir, Dispatchers.IO, availableBytes = { Long.MAX_VALUE })), now = { now })
        val first = submit(oneDriveRequest(remoteBook))
        val second = submit(oneDriveRequest(remoteBook, "PDF"))
        throttledCoordinator().drain()
        assertEquals(1, source.paths.size)
        val waiting = TaskState.Waiting(FrozenSet(listOf(WaitingReason.THROTTLED)))
        assertEquals(waiting, queue.get(first)!!.record.state)
        assertEquals(waiting, queue.get(second)!!.record.state)
        assertEquals(now + 60_000, queue.get(second)!!.retryAt)
        database.close()
        reopen()
        now += 59_000
        throttledCoordinator().drain()
        assertEquals(1, source.paths.size)
        // Local work in another library proceeds while the OneDrive library is throttled.
        source.throttleMillis = null
        state.select(LibraryLocation.Local("test.documents", "first"))
        val local = submit()
        throttledCoordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(local)!!.record.state)
        state.select(LibraryLocation.OneDrive("microsoft-consumers:subject", "drive", "throttled"))
        now += 2_000
        source.paths.clear()
        throttledCoordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(first)!!.record.state)
        // The fixture serves EPUB bytes only; the PDF task ran after the deadline and failed validation.
        assertTrue("作者/书名 (1)/正文.pdf" in source.paths)
        assertTrue(queue.get(second)!!.record.state is TaskState.Finished)
    }

    @Test
    fun copyServicePromotesAutomaticUpdateKeepingFrozenVersionPremise() = runBlocking<Unit> {
        val source = Source(epub("new"))
        val automatic = submit(request(version = FileVersion(BackendKind.LOCAL, source.token)), TaskOrigin.DOWNLOADED_FORMAT_UPDATE)
        val service = CopyService(state, metadata, coordinator(source), queue)
        val result = service.submit(key(), state.current()!!.token)
        assertTrue(result is SubmissionResult.Promoted)
        assertEquals(automatic, (result as SubmissionResult.Promoted).taskId)
        assertEquals(FileVersion(BackendKind.LOCAL, source.token),
            (queue.get(automatic)!!.record.submission.request as TaskRequest.FormatCopy).expectedVersion)
        assertEquals(0, source.opens.get())
        coordinator(source).drain()
        assertArrayEquals(source.bytes, readBytes())
    }

    @Test
    fun symlinkedStagingRootCannotWriteOutsidePrivateTransferDirectory() = runBlocking<Unit> {
        val privateFiles = File(root, "isolated-files").apply { mkdirs() }
        usePrivateFiles(privateFiles)
        val original = Source(epub("old"))
        submit()
        coordinator(original, copyFilesDir = privateFiles).drain()
        val old = requireNotNull(state.find(key()))
        val staging = File(privateFiles, "book-staging")
        assertTrue(staging.deleteRecursively())
        val external = File(root, "outside-staging").apply { mkdirs() }
        Files.createSymbolicLink(staging.toPath(), external.toPath())
        try {
            val task = submit()
            coordinator(Source(epub("new")), copyFilesDir = privateFiles).drain()
            assertTrue(failedKind(task) in setOf(StorageErrorKind.CORRUPT_CONTENT, StorageErrorKind.LOCAL_IO))
            assertTrue(external.listFiles().orEmpty().isEmpty())
            assertEquals(old, state.find(key()))
            assertArrayEquals(original.bytes, readBytes())
        } finally { if (Files.isSymbolicLink(staging.toPath())) Files.delete(staging.toPath()) }
    }

    @Test
    fun symlinkedBooksRootCannotPublishOutsidePrivateTransferDirectory() = runBlocking<Unit> {
        val privateFiles = File(root, "isolated-files").apply { mkdirs() }
        usePrivateFiles(privateFiles)
        val original = Source(epub("old"))
        submit()
        coordinator(original, copyFilesDir = privateFiles).drain()
        val old = requireNotNull(state.find(key()))
        val books = File(privateFiles, "books")
        val savedBooks = File(privateFiles, "saved-books")
        assertTrue(books.renameTo(savedBooks))
        val external = File(root, "outside-books").apply { mkdirs() }
        Files.createSymbolicLink(books.toPath(), external.toPath())
        try {
            val task = submit()
            coordinator(Source(epub("new")), copyFilesDir = privateFiles).drain()
            assertTrue(failedKind(task) in setOf(StorageErrorKind.CORRUPT_CONTENT, StorageErrorKind.LOCAL_IO))
            assertTrue(external.listFiles().orEmpty().isEmpty())
            assertEquals(old, state.find(key()))
            val savedFile = File(savedBooks, "${old.location.libraryId.value}/${old.location.fileGeneration}.book")
            assertArrayEquals(original.bytes, savedFile.readBytes())
        } finally {
            if (Files.isSymbolicLink(books.toPath())) Files.delete(books.toPath())
            assertTrue(savedBooks.renameTo(books))
        }
        assertArrayEquals(original.bytes, readBytes())
    }

    private fun usePrivateFiles(privateFiles: File) {
        files = PrivateBookFiles(privateFiles)
        state = ApplicationStateRepository(database, files, Dispatchers.IO)
        metadata = MetadataRepository(database, state, File(root, "imports"), Dispatchers.IO)
    }

    @Test
    fun restoredTaskPageRequeuesInterruptedTaskWithoutReadingSource() = runBlocking<Unit> {
        val source = Source(epub("old"))
        submit()
        coordinator(source).drain()
        val old = requireNotNull(state.find(key()))
        source.opens.set(0)
        val interrupted = submit()
        assertEquals(interrupted, queue.claim(0, { emptySet() }, { true })!!.record.id)
        queue.update(interrupted) { it.copy(stage = TaskStage.FORMAT_PUBLISH,
            record = it.record.copy(state = TaskState.Running(TaskStage.FORMAT_PUBLISH))) }
        database.close()
        reopen()
        val driver = coordinator(source)
        val store = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) { taskPage(driver).also { store.put("tasks", it); it.setVisible(true) } }
        try {
            val restored = withTimeout(10_000) {
                var shown: TaskRecord? = null
                while (shown?.state != TaskState.Queued) {
                    delay(20)
                    shown = withContext(Dispatchers.Main) { viewModel.records.firstOrNull { it.id == interrupted } }
                }
                requireNotNull(shown)
            }
            assertEquals(interrupted, restored.id)
            assertEquals(TaskState.Queued, queue.get(interrupted)!!.record.state)
            assertEquals(0, source.opens.get())
            assertEquals(old, state.find(key()))
            assertArrayEquals(source.bytes, readBytes())
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }

    @Test
    fun restoringTaskPageDoesNotResetAnActivelyExecutingTask() = runBlocking<Unit> {
        val source = Source(epub("active")).apply { block = true }
        val task = submit()
        val coordinator = coordinator(source)
        val driver = launch(Dispatchers.Default) { coordinator.drain() }
        val store = ViewModelStore()
        try {
            assertTrue(source.started.await(10, TimeUnit.SECONDS))
            val viewModel = withContext(Dispatchers.Main) { taskPage(coordinator).also { store.put("tasks", it); it.setVisible(true) } }
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { viewModel.records.none { it.id == task } }) delay(20)
            }
            assertTrue(withContext(Dispatchers.Main) { viewModel.records.single { it.id == task }.state } is TaskState.Running)
            assertTrue(queue.get(task)!!.record.state is TaskState.Running)
            assertEquals(1, source.opens.get())
        } finally {
            source.release.countDown()
            withTimeout(10_000) { driver.join() }
            withContext(Dispatchers.Main) { store.clear() }
        }
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertArrayEquals(source.bytes, readBytes())
    }

    /** The task page as production builds it, restoring through [coordinator]; it never submits or wakes source work. */
    private fun taskPage(coordinator: TaskCoordinator) = TaskViewModel(queue, { false }, {}, { error("Restoration must not wake the queue") },
        context.applicationContext as Application, { false }, coordinator::restorePending)

    private fun partialFile(task: TaskId): File = File(context.filesDir, "book-staging/${task.value}")
        .listFiles().orEmpty().single { it.extension == "part" }

    private suspend fun pauseTransfer(source: Source, request: TaskRequest.FormatCopy = request()): TaskId {
        source.blockAfterChunk = true
        val task = submit(request)
        val driver = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { coordinator(source).drain() }
        try {
            assertTrue(source.started.await(10, TimeUnit.SECONDS))
            assertTrue(queue.control(task, TaskControl.PAUSE))
        } finally { source.release.countDown() }
        withTimeout(10_000) { driver.join() }
        assertTrue(queue.get(task)!!.record.state is TaskState.Paused)
        return task
    }

    private suspend fun submitCheck(key: CopyKey): TaskId =
        (queue.submit(TaskSubmission(TaskRequest.FormatCheck(key), TaskOrigin.DOWNLOADED_FORMAT_UPDATE)) as SubmissionResult.Created).taskId

    private fun coordinator(source: Source, availableBytes: () -> Long = { Long.MAX_VALUE }, copyFilesDir: File = context.filesDir) =
        coordinator(source.sources, availableBytes, copyFilesDir)

    private fun coordinator(sources: LibrarySources, availableBytes: () -> Long = { Long.MAX_VALUE }, copyFilesDir: File = context.filesDir) =
        TaskCoordinator(queue, listOf(FormatCopyTaskHandler(state, metadata, queue, sources, copyFilesDir, Dispatchers.IO, availableBytes)))

    /** Source I/O fixture. Backend policies come from the production sources; [unchanged] re-reads the token. */
    private class Source(@Volatile var bytes: ByteArray, @Volatile var token: String = hash(bytes)) {
        @Volatile var knownSize: Long? = null
        @Volatile var estimate: Long? = null
        @Volatile var failure: StorageErrorKind? = null
        @Volatile var rangeFailure: StorageErrorKind? = null
        @Volatile var changeAfterOpen = false
        @Volatile var failAfterFirstChunk = false
        @Volatile var block = false
        @Volatile var blockAfterChunk = false
        @Volatile var networkAfterChunk = false
        @Volatile var rangeSupported = true
        @Volatile var throttleMillis: Long? = null
        @Volatile var missing = emptySet<String>()
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val rangeOffsets = mutableListOf<Long>()
        val bytesRead = java.util.concurrent.atomic.AtomicLong()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val sources = LibrarySources { backend -> on(SourcePolicies.sources.of(backend)) }
        private fun on(policy: LibrarySource): LibrarySource = object : LibrarySource by policy {
            override suspend fun lookup(location: LibraryLocation, path: RelativeSourcePath, control: suspend () -> Unit): SourceFile {
                control()
                val observed = version(location, path)
                control()
                val exact = knownSize
                val estimated = estimate ?: exact
                return object : SourceFile {
                    override val version = observed
                    override val size = exact
                    override val estimatedSize = estimated
                    override val contentSha256 = observed.token.takeIf { policy.backend == BackendKind.LOCAL }
                    override suspend fun open() = this@Source.open()
                    override suspend fun openRange(offset: Long) = this@Source.openRange(location, offset, observed)
                }
            }
            override suspend fun unchanged(location: LibraryLocation, path: RelativeSourcePath, version: FileVersion,
                control: suspend () -> Unit): Boolean {
                control()
                return version(location, path) == version
            }
        }
        fun version(location: LibraryLocation, path: RelativeSourcePath): FileVersion {
            paths.add(path.value)
            failure?.let { throw SourceFailure(StorageError(it)) }
            throttleMillis?.let { throw SourceFailure(StorageError(StorageErrorKind.THROTTLED), true, it) }
            if (path.value in missing) throw SourceFailure(StorageError(StorageErrorKind.SOURCE_MISSING))
            return FileVersion(location.backend, token)
        }
        fun open(): InputStream {
            failure?.let { throw SourceFailure(StorageError(it)) }
            opens.incrementAndGet()
            return stream(0)
        }
        fun openRange(location: LibraryLocation, offset: Long, expectedVersion: FileVersion): InputStream? {
            assertEquals(FileVersion(location.backend, token), expectedVersion)
            rangeOffsets.add(offset)
            rangeFailure?.let { throw SourceFailure(StorageError(it)) }
            return if (rangeSupported) stream(offset) else null
        }
        private fun stream(startOffset: Long): InputStream {
            if (changeAfterOpen) token = "changed"
            val shouldBlock = block
            val shouldBlockAfterChunk = blockAfterChunk
            block = false
            blockAfterChunk = false
            return object : ByteArrayInputStream(bytes.copyOfRange(startOffset.toInt(), bytes.size)) {
                private var firstRead = true
                private var chunks = 0
                private var blockedAfterChunk = false
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if ((firstRead && shouldBlock) || (chunks == 1 && shouldBlockAfterChunk && !blockedAfterChunk)) {
                        firstRead = false
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Source stream was not released" }
                        if (shouldBlockAfterChunk) { blockedAfterChunk = true; return 0 }
                    }
                    if (networkAfterChunk && chunks > 0)
                        throw SourceFailure(StorageError(StorageErrorKind.NO_NETWORK), transient = true)
                    if (failAfterFirstChunk && chunks > 0) throw IOException("Injected stream interruption")
                    val result = super.read(buffer, offset,
                        if (failAfterFirstChunk || networkAfterChunk || (shouldBlockAfterChunk && chunks == 0)) minOf(length, 64) else length)
                    chunks++
                    if (result > 0) bytesRead.addAndGet(result.toLong())
                    return result
                }
                override fun close() { closes.incrementAndGet(); super.close() }
            }
        }
    }

    /** The production sources over the given backends, with the local grant each library was listed with. */
    private fun productionSources(local: LocalSourceBackend, remote: OneDriveSourceBackend): LibrarySources {
        val localSource = LocalLibrarySource(local) { location -> state.accessKey(location) }
        val oneDrive = OneDriveLibrarySource(remote)
        return LibrarySources { if (it == BackendKind.LOCAL) localSource else oneDrive }
    }

    private companion object {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun epub(text: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
            """.trimIndent(), "content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">fixture</dc:identifier><dc:title>Fixture</dc:title><dc:language>zh</dc:language></metadata><manifest><item id="text" href="text.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="text"/></spine></package>
            """.trimIndent(), "text.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Fixture</title></head><body><p>$text</p></body></html>")
                .forEach { (name, contents) -> zip.putNextEntry(ZipEntry(name)); zip.write(contents.toByteArray()); zip.closeEntry() }
        }
        output.toByteArray()
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, files, Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        metadata = MetadataRepository(database, state, File(root, "imports"), Dispatchers.IO)
    }

    private suspend fun activate(name: String): BookKey {
        val selection = state.select(LibraryLocation.Local("test.documents", name))
        val fixture = CalibreFixture.create(File(root, "$name.db"), bookUuid = bookUuid)
        val identity = requireNotNull(metadata.importSnapshot(selection.token, fixture))
        libraries.add(identity.id)
        return BookKey(identity.id, 1, bookUuid)
    }

    private suspend fun activateOneDrive(name: String): BookKey {
        state.select(LibraryLocation.OneDrive("microsoft-consumers:subject", "drive", name))
        return BookKey(reimport(name).id, 1, bookUuid).also { libraries.add(it.libraryId) }
    }

    private suspend fun reimport(name: String, lastModified: String = "2026-01-02 03:04:05.000000+00:00", epubSize: Long = 42,
        bookPath: String = "作者/书名 (1)"): LibraryIdentity {
        val fixture = CalibreFixture.create(File(root, "$name-${UUID.randomUUID()}.db"), libraryUuid = UUID.nameUUIDFromBytes(name.toByteArray()),
            bookUuid = bookUuid, lastModified = lastModified, epubSize = epubSize, bookPath = bookPath)
        return requireNotNull(metadata.importSnapshot(state.current()!!.token, fixture,
            sourceVersion = FileVersion(BackendKind.ONEDRIVE, UUID.randomUUID().toString()), checksCopy = SourcePolicies.sources.of(BackendKind.ONEDRIVE)::checksCopy))
    }

    private suspend fun pendingChecks() = queue.list().map { it.record }
        .filter { it.state !is TaskState.Finished && it.submission.request is TaskRequest.FormatCheck }.map { it.submission.request }

    private fun oneDriveRequest(target: BookKey, format: String = "EPUB") = TaskRequest.FormatCopy(FormatResource(target, BookFormat.parse(format),
        SourceFileLocator.Relative(BackendKind.ONEDRIVE, RelativeSourcePath("作者/书名 (1)/正文.${format.lowercase()}"))))

    /** A metadata sync stand-in: imports the library again with the book at [bookPath]. */
    private inner class FixtureSync(private val name: String, private val bookPath: String, private val during: suspend () -> Unit = {}) : TaskHandler {
        val tasks = mutableListOf<TaskId>()
        val origins = mutableListOf<TaskOrigin>()
        suspend fun request(origin: TaskOrigin): TaskId {
            val selection = state.current()!!
            val result = queue.submit(TaskSubmission(TaskRequest.CandidateConfiguration(
                CandidateContext(selection.token, BackendKind.ONEDRIVE, UUID.randomUUID()), "fixture_sync"), origin))
            val id = (result as SubmissionResult.Created).taskId
            tasks.add(id); origins.add(origin)
            return id
        }
        override fun supports(request: TaskRequest) = (request as? TaskRequest.CandidateConfiguration)?.operation == "fixture_sync"
        override fun controls(stage: TaskStage) = TaskControls(false, true, false, false)
        override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(TaskStage.CANDIDATE_ACCESS, null)
        override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
            during()
            val fixture = CalibreFixture.create(File(root, "$name-${UUID.randomUUID()}.db"), libraryUuid = UUID.nameUUIDFromBytes(name.toByteArray()),
                bookUuid = bookUuid, bookPath = bookPath, lastModified = "2026-01-02 03:04:05.000000+00:00")
            requireNotNull(metadata.importSnapshot(state.current()!!.token, fixture, entry.record.id.value,
                FileVersion(BackendKind.ONEDRIVE, UUID.randomUUID().toString()), SourcePolicies.sources.of(BackendKind.ONEDRIVE)::checksCopy))
            return StageOutcome.Complete(cachePublished = true)
        }
    }

    private fun coordinator(source: Source, sync: FixtureSync) = TaskCoordinator(queue, listOf(
        FormatCopyTaskHandler(state, metadata, queue, source.sources, context.filesDir, Dispatchers.IO, { Long.MAX_VALUE }, sync::request), sync))

    private fun key(format: String = "EPUB", target: BookKey = book) = CopyKey(target, BookFormat.parse(format))

    private fun request(format: String = "EPUB", target: BookKey = book, version: FileVersion? = null) =
        TaskRequest.FormatCopy(FormatResource(target, BookFormat.parse(format), SourceFileLocator.Relative(BackendKind.LOCAL, RelativeSourcePath("作者/书名 (1)/正文.${format.lowercase()}"))), version)

    private suspend fun submit(request: TaskRequest.FormatCopy = request(), origin: TaskOrigin = TaskOrigin.USER_DOWNLOAD): TaskId =
        (queue.submit(TaskSubmission(request, origin)) as SubmissionResult.Created).taskId

    private suspend fun readBytes(key: CopyKey = key()): ByteArray {
        val result = PrivateCopyReader(state, files, Dispatchers.IO, state.copyAccess).read(key) as CopyReadResult.Available
        return result.handle.use { it.input.readBytes() }
    }

    private suspend fun failedKind(task: TaskId): StorageErrorKind =
        (((queue.get(task)!!.record.state as TaskState.Finished).result as TaskResult.Failed)
            .failure.error as TaskError.Source).error.kind
}
