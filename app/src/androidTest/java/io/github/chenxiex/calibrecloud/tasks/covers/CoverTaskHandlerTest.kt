package io.github.chenxiex.calibrecloud.tasks.covers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.CalibreFixture
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.StateSchemaHistory
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.SourcePolicies
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.ByteBuffer
import java.util.zip.CRC32

private const val FIRST_PATH = "作者/书名 (1)/cover.jpg"
private const val SECOND_PATH = "作者/第二本 (2)/cover.jpg"

/** Real SQLite, decoding and files; independent source fixtures never touch an authorized library. */
@RunWith(AndroidJUnit4::class)
class CoverTaskHandlerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var metadata: MetadataRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var covers: CoverRepository
    private lateinit var book: BookKey
    private val uuid = UUID.randomUUID()

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "cover-test-${UUID.randomUUID()}").apply { mkdirs() }
        databaseName = "cover-test-${UUID.randomUUID()}.db"
        reopen()
        book = activate("first", uuid)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    @Test
    fun explicitRequestDeduplicatesAndScaledCacheSurvivesReopenWithoutSourceReads() = runBlocking<Unit> {
        val source = Source(image(1024, 1536, Color.RED))
        assertNull(covers.read(book))
        assertEquals(0, source.opens.get())
        val service = CoverService(state, metadata, coordinator(source), queue)
        val first = service.submit(listOf(book), state.current()!!.token)
        val again = service.submit(listOf(book), state.current()!!.token)
        assertTrue(first is SubmissionResult.Created)
        assertTrue(again is SubmissionResult.Reused)
        assertEquals((first as SubmissionResult.Created).taskId, (again as SubmissionResult.Reused).taskId)
        assertEquals(0, source.opens.get())
        coordinator(source).drain()
        assertCompleted(first.taskId)
        assertImage(book, Color.RED, 256, 384)
        assertEquals(listOf("作者/书名 (1)/cover.jpg"), source.paths)
        database.close()
        reopen()
        assertImage(book, Color.RED, 256, 384)
        assertEquals(1, source.opens.get())
    }

    @Test
    fun aCorruptOrOversizedCoverKeepsItsPlaceholderWithoutStoppingTheBatch() = runBlocking<Unit> {
        val second = second()
        for (bytes in listOf("invalid image".toByteArray(), ByteArray((CoverRepository.MAX_ENCODED_BYTES + 1).toInt()), oversizedDimensions())) {
            val source = Source(image(64, 96, Color.RED)).apply { overrides[SECOND_PATH] = bytes }
            val task = submit(second, book)
            coordinator(source).drain()
            val result = (queue.get(task)!!.record.state as TaskState.Finished).result as TaskResult.CompletedWithBookFailures
            assertEquals(setOf(BookFailure(second, TaskError.Source(StorageError(StorageErrorKind.CORRUPT_CONTENT)))), result.failures.toSet())
            assertNull(covers.read(second))
            assertImage(book, Color.RED, 64, 96)
            assertFalse(File(root, "files/cover-staging/${task.value}").exists())
        }
    }

    @Test
    fun aResumedBatchSkipsTheCoversItAlreadyPublished() = runBlocking<Unit> {
        val second = second()
        val source = Source(image(64, 96, Color.RED)).apply { failure = StorageErrorKind.NO_NETWORK; failAfterOpens = 1 }
        val task = submit(book, second)
        coordinator(source).drain()
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), queue.get(task)!!.record.state)
        assertImage(book, Color.RED, 64, 96)
        source.failure = null
        database.writableDatabase.execSQL("UPDATE queued_tasks SET retry_at = 0 WHERE task_id = ?", arrayOf(task.value.toString()))
        coordinator(source).drain()
        assertCompleted(task)
        assertImage(second, Color.RED, 64, 96)
        assertEquals(listOf(FIRST_PATH, SECOND_PATH), source.paths)
    }

    @Test
    fun aNewerPageBatchEndsTheRunningOneAfterItsCurrentCover() = runBlocking<Unit> {
        val second = second()
        val source = Source(image(64, 96, Color.RED)).apply { block = true }
        val service = CoverService(state, metadata, coordinator(source), queue)
        val older = (service.submit(listOf(book, second), state.current()!!.token) as SubmissionResult.Created).taskId
        val driver = launch(Dispatchers.Default) { coordinator(source).drain() }
        val newer: TaskId
        try {
            assertTrue(source.started.await(10, TimeUnit.SECONDS))
            newer = (service.submit(listOf(second), state.current()!!.token) as SubmissionResult.Created).taskId
        } finally { source.release.countDown() }
        withTimeout(10_000) { driver.join() }
        // The current cover is still published; the replaced batch does not read the next one.
        assertImage(book, Color.RED, 64, 96)
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(older)!!.record.state)
        assertCompleted(newer)
        assertImage(second, Color.RED, 64, 96)
        assertEquals(listOf(FIRST_PATH, SECOND_PATH), source.paths)
    }

    @Test
    fun coversOfABatchLoadAtOnceUpToTheBackendLimit() = runBlocking<Unit> {
        val second = second()
        val source = Source(image(64, 96, Color.RED)).apply { parallel = 2; block = true }
        val task = submit(book, second)
        val driver = launch(Dispatchers.Default) { coordinator(source).drain() }
        try {
            assertTrue(source.started.await(10, TimeUnit.SECONDS))
            // Whichever cover opened first is still being read while the other one is published.
            suspend fun published(key: BookKey) = covers.read(key)?.also { it.recycle() } != null
            withTimeout(10_000) { while (!published(book) && !published(second)) kotlinx.coroutines.delay(20) }
            assertNotEquals(published(book), published(second))
        } finally { source.release.countDown() }
        withTimeout(10_000) { driver.join() }
        assertCompleted(task)
        assertImage(book, Color.RED, 64, 96)
        assertImage(second, Color.RED, 64, 96)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertFalse(File(root, "files/cover-staging/${task.value}").exists())
    }

    @Test
    fun aBatchFailureStopsTheCoversInFlightAndKeepsNoStaging() = runBlocking<Unit> {
        val second = second()
        val source = Source(image(64, 96, Color.RED)).apply {
            parallel = 2; block = true; failure = StorageErrorKind.NO_NETWORK; failurePath = SECOND_PATH
        }
        val task = submit(book, second)
        val driver = launch(Dispatchers.Default) { coordinator(source).drain() }
        try {
            assertTrue(source.started.await(10, TimeUnit.SECONDS))
            // The second cover fails while the first is still being read.
            withTimeout(10_000) { while (source.failed.get() == 0) kotlinx.coroutines.delay(20) }
        } finally { source.release.countDown() }
        withTimeout(10_000) { driver.join() }
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), queue.get(task)!!.record.state)
        assertNull(covers.read(book))
        assertNull(covers.read(second))
        assertEquals(source.opens.get(), source.closes.get())
        assertFalse(File(root, "files/cover-staging/${task.value}").exists())
    }

    @Test
    fun coversPublishedAtOnceAreNeverCollectedBeforeTheyAreRecorded() = runBlocking<Unit> {
        // Quota collection after one publication must not remove a cover another publication is moving in.
        val second = second()
        val task = submit(book, second)
        repeat(10) { round ->
            val staged = listOf(book, second).map { key ->
                File(root, "staged-$round-${key.sourceId}.png").apply { writeBytes(image(64, 96, if (round % 2 == 0) Color.RED else Color.BLUE)) }
            }
            listOf(book, second).zip(staged).map { (key, file) ->
                launch(Dispatchers.IO) { assertTrue(covers.publish(key, file, metadata.currentImport()!!.generation, task.value)) }
            }.forEach { it.join() }
            assertImage(book, if (round % 2 == 0) Color.RED else Color.BLUE, 64, 96)
            assertImage(second, if (round % 2 == 0) Color.RED else Color.BLUE, 64, 96)
        }
    }

    @Test
    fun aQueuedPageBatchIsReplacedAtOnce() = runBlocking<Unit> {
        val service = CoverService(state, metadata, coordinator(Source(image(64, 96, Color.RED))), queue)
        val older = (service.submit(listOf(book), state.current()!!.token) as SubmissionResult.Created).taskId
        val newer = (service.submit(listOf(second()), state.current()!!.token) as SubmissionResult.Created).taskId
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(older)!!.record.state)
        assertEquals(TaskState.Queued, queue.get(newer)!!.record.state)
    }

    @Test
    fun theSingleReadIsPublishedWithoutRereadingTheSource() = runBlocking<Unit> {
        // The version names the bytes decoded; a later source change is not checked again before publication.
        val source = Source(image(64, 96, Color.BLUE)).apply { changeVersionOnOpen = true }
        val task = submit()
        coordinator(source).drain()
        assertCompleted(task)
        assertImage(book, Color.BLUE, 64, 96)
        assertEquals(1, source.opens.get())
    }

    @Test
    fun pauseResumeAndCancelCloseSourceAndNeverExposeAnIncompleteImage() = runBlocking<Unit> {
        for (control in listOf(TaskControl.PAUSE, TaskControl.CANCEL)) {
            val source = Source(image(64, 96, Color.BLUE)).apply { block = true }
            val task = submit()
            val driver = launch(Dispatchers.Default) { coordinator(source).drain() }
            try {
                assertTrue(source.started.await(10, TimeUnit.SECONDS))
                assertNull(covers.read(book))
                assertTrue(queue.control(task, control))
            } finally { source.release.countDown() }
            withTimeout(10_000) { driver.join() }
            assertEquals(1, source.closes.get())
            assertNull(covers.read(book))
            assertFalse(File(root, "files/cover-staging/${task.value}").exists())
            if (control == TaskControl.PAUSE) {
                assertTrue(queue.get(task)!!.record.state is TaskState.Paused)
                database.close()
                reopen()
                assertTrue(queue.control(task, TaskControl.RESUME))
                coordinator(source).drain()
                assertCompleted(task)
                assertImage(book, Color.BLUE, 64, 96)
                assertEquals(2, source.opens.get())
                // Start the cancellation case with a different, uncached complete identity.
                book = activate("cancel", UUID.randomUUID())
            } else assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(task)!!.record.state)
        }
    }

    @Test
    fun libraryAndUuidAreBothPartOfTheCacheIdentity() = runBlocking<Unit> {
        val first = book
        submit()
        coordinator(Source(image(64, 96, Color.RED))).drain()
        assertNull(covers.read(BookKey(first.libraryId, first.sourceId, UUID.randomUUID())))
        book = activate("second", uuid)
        assertNotEquals(first.libraryId, book.libraryId)
        assertNull(covers.read(book))
        submit()
        coordinator(Source(image(64, 96, Color.BLUE))).drain()
        assertImage(first, Color.RED, 64, 96)
        assertImage(book, Color.BLUE, 64, 96)
    }

    @Test
    fun quotaCollectsOnlyCoverFilesAndKeepsTheMostRecentlyAccessedImage() = runBlocking<Unit> {
        val encodedSize = maxOf(image(64, 96, Color.RED).size, image(64, 96, Color.BLUE).size).toLong()
        covers = CoverRepository(database, state, File(root, "files"), Dispatchers.IO, encodedSize)
        val first = book
        submit()
        coordinator(Source(image(64, 96, Color.RED))).drain()
        val protected = File(root, "files/books/complete.book").apply { parentFile!!.mkdirs(); writeText("complete book") }
        book = activate("quota", UUID.randomUUID())
        submit()
        coordinator(Source(image(64, 96, Color.BLUE))).drain()
        assertNull(covers.read(first))
        assertImage(book, Color.BLUE, 64, 96)
        assertEquals("complete book", protected.readText())
        assertEquals(1, File(root, "files/covers").walkTopDown().count { it.isFile })
    }

    @Test
    fun versionThreeUpgradePreservesImportedMetadataManifestAndQueuedTasks() = runBlocking<Unit> {
        val imported = requireNotNull(metadata.currentImport())
        val task = submit()
        val queued = queue.get(task)
        database.writableDatabase.execSQL("""
            INSERT INTO downloaded_copies(library_id,source_id,source_uuid,format,file_generation,title,
                size_bytes,version_backend,version_token,source_availability) VALUES(?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(), arrayOf<Any>(book.libraryId.value.toString(), book.sourceId, book.sourceUuid.toString(),
            "EPUB", UUID.randomUUID().toString(), "Preserved book", 123L, "local", "version-3", "available"))
        val manifest = state.find(CopyKey(book, BookFormat.parse("EPUB")))
        assertNotNull(manifest)
        StateSchemaHistory.downgrade(database.writableDatabase, 3)
        database.close()
        reopen()
        assertEquals(ApplicationStateDatabase.VERSION, database.readableDatabase.version)
        assertEquals(imported, metadata.currentImport())
        assertEquals(manifest, state.find(CopyKey(book, BookFormat.parse("EPUB"))))
        assertEquals(queued, queue.get(task))
        assertNull(covers.read(book))
    }

    @Test
    fun aUserDownloadRunsAtTheNextSafeBoundaryAndTheBatchResumes() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val source = Source(image(64, 96, Color.RED)).apply {
            block = true
            onOpen = { events.add("cover") }
        }
        val coverHandler = CoverTaskHandler(state, metadata, covers, source.sources, Dispatchers.IO, queue)
        val download = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.FormatCopy
            override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(entry.stage, entry.checkpoint)
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                return if (entry.stage == TaskStage.FORMAT_TRANSFER) {
                    events.add("download")
                    StageOutcome.Advance(TaskStage.FORMAT_PUBLISH)
                } else StageOutcome.Complete()
            }
        }
        val coordinator = TaskCoordinator(queue, listOf(coverHandler, download))
        val batch = submit(book, second())
        val driver = launch(Dispatchers.Default) { coordinator.drain() }
        val high: SubmissionResult
        try {
            assertTrue(source.started.await(10, TimeUnit.SECONDS))
            high = coordinator.submit(TaskSubmission(TaskRequest.FormatCopy(FormatResource(book,
                BookFormat.parse("EPUB"), SourceFileLocator.Relative(BackendKind.LOCAL,
                    RelativeSourcePath("作者/书名 (1)/正文.epub")))), TaskOrigin.USER_DOWNLOAD))
            assertTrue(high is SubmissionResult.Created)
            assertEquals(listOf("cover"), events)
        } finally { source.release.countDown() }
        withTimeout(10_000) { driver.join() }
        // The interrupted cover is read again after the download; the batch then goes on to the next one.
        assertEquals(listOf("cover", "download", "cover", "cover"), events)
        assertCompleted((high as SubmissionResult.Created).taskId)
        assertCompleted(batch)
        assertImage(book, Color.RED, 64, 96)
        assertFalse(File(root, "files/cover-staging/${batch.value}").exists())
    }

    @Test
    fun sourceNetworkAndAuthorizationFailuresSuspendTheBatch() = runBlocking<Unit> {
        submit()
        coordinator(Source(image(64, 96, Color.RED))).drain()
        val second = second()
        for (kind in listOf(StorageErrorKind.NO_NETWORK, StorageErrorKind.AUTHORIZATION_EXPIRED)) {
            val task = submit(second)
            val source = Source(image(64, 96, Color.BLUE)).apply { failure = kind }
            coordinator(source).drain()
            assertEquals(0, source.opens.get())
            // A local library waits for directory re-authorization instead of failing.
            val reason = if (kind == StorageErrorKind.NO_NETWORK) WaitingReason.NETWORK else WaitingReason.DIRECTORY_AUTHORIZATION
            assertEquals(TaskState.Waiting(FrozenSet(listOf(reason))), queue.get(task)!!.record.state)
            assertImage(book, Color.RED, 64, 96)
            assertNull(covers.read(second))
            if (queue.get(task)!!.record.state is TaskState.Waiting) {
                assertTrue(queue.control(task, TaskControl.CANCEL))
                coordinator(source).drain()
            }
        }
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, databaseName)
        val files = File(root, "files").apply { mkdirs() }
        state = ApplicationStateRepository(database, PrivateBookFiles(files), Dispatchers.IO)
        metadata = MetadataRepository(database, state, File(root, "imports"), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        covers = CoverRepository(database, state, files, Dispatchers.IO)
    }

    private suspend fun activate(name: String, sourceUuid: UUID): BookKey {
        val selection = state.select(LibraryLocation.Local("test.documents", name))
        val fixture = CalibreFixture.create(File(root, "$name.db"), bookUuid = sourceUuid)
        SQLiteDatabase.openDatabase(fixture.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO books(id,uuid,title,timestamp,path,series_index,has_cover) VALUES(2,?,'第二本','2026-01-02 03:04:05+00:00','作者/第二本 (2)',1,1)",
                arrayOf(UUID.randomUUID().toString()))
        }
        val identity = requireNotNull(metadata.importSnapshot(selection.token, fixture))
        return BookKey(identity.id, 1, sourceUuid)
    }

    private fun coordinator(source: Source) = TaskCoordinator(queue,
        listOf(CoverTaskHandler(state, metadata, covers, source.sources, Dispatchers.IO, queue)))

    /** The second book of the active fixture library. */
    private suspend fun second(): BookKey =
        BookKey(book.libraryId, 2, metadata.currentImport()!!.metadata.books.single { it.sourceId == 2L }.sourceUuid)

    private suspend fun submit(vararg books: BookKey = arrayOf(book)) = (queue.submit(TaskSubmission(
        TaskRequest.CoverLoad(book.libraryId, FrozenSet(books.toList())), TaskOrigin.VISIBLE_COVER)) as SubmissionResult.Created).taskId

    private suspend fun assertCompleted(task: TaskId) =
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)

    private suspend fun assertImage(key: BookKey, color: Int, width: Int, height: Int) {
        val bitmap = requireNotNull(covers.read(key))
        try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
            assertEquals(color, bitmap.getPixel(0, 0))
        } finally { bitmap.recycle() }
    }

    /** Cover I/O fixture; backend policies come from the production sources. */
    private class Source(private val bytes: ByteArray) {
        val sources = LibrarySources { backend ->
            object : LibrarySource by SourcePolicies.sources.of(backend) {
                override val parallelReads get() = this@Source.parallel
                override suspend fun openCover(location: LibraryLocation, path: RelativeSourcePath, targetWidth: Int,
                    targetHeight: Int, control: suspend () -> Unit): SourceStream {
                    control()
                    val opened = this@Source.version(location, path)
                    return SourceStream(this@Source.open(path), opened)
                }
            }
        }
        val opens = AtomicInteger()
        val failed = AtomicInteger()
        val closes = AtomicInteger()
        val paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val blockNext = java.util.concurrent.atomic.AtomicBoolean()
        /** The next opened cover blocks on its first read until [release]; covers read at once race for it. */
        var block: Boolean
            get() = blockNext.get()
            set(value) = blockNext.set(value)
        var changeVersionOnOpen = false
        /** Covers read at once; one keeps the order of the other tests deterministic. */
        var parallel = 1
        var failure: StorageErrorKind? = null
        /** Fails only the cover at this path; null fails every cover. */
        var failurePath: String? = null
        /** Opens that succeed before [failure] applies; null applies it from the start. */
        var failAfterOpens: Int? = null
        val overrides = mutableMapOf<String, ByteArray>()
        var onOpen: () -> Unit = {}
        private var version = "initial"
        fun version(location: LibraryLocation, path: RelativeSourcePath? = null): FileVersion {
            failure?.let { if (opens.get() >= (failAfterOpens ?: 0) && (failurePath == null || failurePath == path?.value)) {
                // A failure of one path waits until another cover is being read, so it meets a cover in flight.
                if (failurePath != null) check(started.await(10, TimeUnit.SECONDS))
                failed.incrementAndGet()
                throw SourceFailure(StorageError(it))
            } }
            return FileVersion(location.backend, version)
        }
        fun open(path: RelativeSourcePath): InputStream {
            opens.incrementAndGet()
            onOpen()
            paths.add(path.value)
            if (changeVersionOnOpen) version = "changed"
            val shouldBlock = blockNext.getAndSet(false)
            return object : ByteArrayInputStream(overrides[path.value] ?: bytes) {
                private var first = true
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first && shouldBlock) {
                        first = false
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return 0
                    }
                    return super.read(buffer, offset, length)
                }
                override fun close() { closes.incrementAndGet(); super.close() }
            }
        }
    }

    private fun image(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    /** A valid IHDR advertises an unsafe width; no giant bitmap is allocated by the fixture. */
    private fun oversizedDimensions(): ByteArray {
        val bytes = image(1, 1, Color.RED)
        ByteBuffer.wrap(bytes, 16, 4).putInt(40000)
        val crc = CRC32().apply { update(bytes, 12, 17) }
        ByteBuffer.wrap(bytes, 29, 4).putInt(crc.value.toInt())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals("Fixture must exercise the decode dimension guard", 40000, bounds.outWidth)
        return bytes
    }
}
