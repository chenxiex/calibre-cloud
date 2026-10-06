package io.github.chenxiex.calibrecloud.tasks.covers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.CalibreFixture
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.copies.FormatSource
import io.github.chenxiex.calibrecloud.tasks.copies.FormatSourceFailure
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import io.github.chenxiex.calibrecloud.ui.CoverViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
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
        val service = CoverService(state, metadata, coordinator(source))
        val first = service.submit(book, state.current()!!.token)
        val again = service.submit(book, state.current()!!.token)
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
    fun corruptOversizedAndChangedSourceRetainThePreviousCompleteImage() = runBlocking<Unit> {
        submit()
        coordinator(Source(image(64, 96, Color.RED))).drain()
        for (source in listOf(Source("invalid image".toByteArray()),
            Source(ByteArray((CoverRepository.MAX_ENCODED_BYTES + 1).toInt())),
            Source(oversizedDimensions()),
            Source(image(64, 96, Color.BLUE)).apply { changeVersionOnOpen = true })) {
            val task = submit()
            coordinator(source).drain()
            val failure = (queue.get(task)!!.record.state as TaskState.Finished).result as TaskResult.Failed
            val kind = (failure.failure.error as TaskError.Source).error.kind
            assertTrue(kind in setOf(StorageErrorKind.CORRUPT_CONTENT, StorageErrorKind.VERSION_CONFLICT))
            assertImage(book, Color.RED, 64, 96)
            assertFalse(File(root, "files/cover-staging/${task.value}").exists())
        }
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
        """.trimIndent(), arrayOf(book.libraryId.value.toString(), book.sourceId, book.sourceUuid.toString(),
            "EPUB", UUID.randomUUID().toString(), "Preserved book", 123L, "local", "version-3", "available"))
        val manifest = state.find(CopyKey(book, BookFormat.parse("EPUB")))
        assertNotNull(manifest)
        // The old schema differs only by the new independent cover table.
        database.writableDatabase.execSQL("DROP TABLE cover_cache")
        database.writableDatabase.version = 3
        database.close()
        reopen()
        assertEquals(4, database.readableDatabase.version)
        assertEquals(imported, metadata.currentImport())
        assertEquals(manifest, state.find(CopyKey(book, BookFormat.parse("EPUB"))))
        assertEquals(queued, queue.get(task))
        assertNull(covers.read(book))
    }

    @Test
    fun userDownloadRunsBetweenCoverTasksWithoutPreemptingTheCurrentCover() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val source = Source(image(64, 96, Color.RED)).apply {
            block = true
            onOpen = { events.add("cover") }
        }
        val coverHandler = CoverTaskHandler(state, metadata, covers, source, source, Dispatchers.IO)
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
        val first = submit()
        val secondMetadata = metadata.currentImport()!!.metadata.books.single { it.sourceId == 2L }
        val second = BookKey(book.libraryId, 2, secondMetadata.sourceUuid)
        val secondTask = coordinator.submit(TaskSubmission(TaskRequest.CoverLoad(second), TaskOrigin.VISIBLE_COVER))
        assertTrue(secondTask is SubmissionResult.Created)
        val driver = launch(Dispatchers.Default) { coordinator.drain() }
        try {
            assertTrue(source.started.await(10, TimeUnit.SECONDS))
            val high = coordinator.submit(TaskSubmission(TaskRequest.FormatCopy(FormatResource(book,
                BookFormat.parse("EPUB"), SourceFileLocator.Relative(BackendKind.LOCAL,
                    RelativeSourcePath("作者/书名 (1)/正文.epub")))), TaskOrigin.USER_DOWNLOAD))
            assertTrue(high is SubmissionResult.Created)
            assertEquals(listOf("cover"), events)
        } finally { source.release.countDown() }
        withTimeout(10_000) { driver.join() }
        assertCompleted(first)
        assertCompleted((secondTask as SubmissionResult.Created).taskId)
        assertEquals(listOf("cover", "download", "cover"), events)
    }

    @Test
    fun sourceNetworkAndAuthorizationFailuresKeepThePreviousImage() = runBlocking<Unit> {
        submit()
        coordinator(Source(image(64, 96, Color.RED))).drain()
        for (kind in listOf(StorageErrorKind.NO_NETWORK, StorageErrorKind.AUTHORIZATION_EXPIRED)) {
            val task = submit()
            val source = Source(image(64, 96, Color.BLUE)).apply { failure = kind }
            coordinator(source).drain()
            assertEquals(0, source.opens.get())
            if (kind == StorageErrorKind.NO_NETWORK) assertTrue(queue.get(task)!!.record.state is TaskState.Waiting)
            else assertTrue(queue.get(task)!!.record.state is TaskState.Finished)
            assertImage(book, Color.RED, 64, 96)
            if (queue.get(task)!!.record.state is TaskState.Waiting) {
                assertTrue(queue.control(task, TaskControl.CANCEL))
                coordinator(source).drain()
            }
        }
    }

    @Test
    fun coverPageRequestsOnlyTheVisibleBookAndHiddenRestoreDoesNotReadSource() = runBlocking<Unit> {
        val source = Source(image(64, 96, Color.RED))
        val coordinator = coordinator(source)
        val service = CoverService(state, metadata, coordinator)
        val store = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            CoverViewModel(state, metadata, covers, queue, coordinator, service::submit).also {
                store.put("covers", it)
                it.setVisible(false)
                it.restore()
            }
        }
        try {
            delay(50)
            assertEquals(0, source.opens.get())
            assertTrue(queue.list().isEmpty())
            withContext(Dispatchers.Main) { viewModel.setVisible(true) }
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { viewModel.image == null }) delay(20)
            }
            assertEquals(1, source.opens.get())
            assertEquals(1, queue.list().size)
            withContext(Dispatchers.Main) { viewModel.paginate(1) }
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { viewModel.image == null }) delay(20)
            }
            assertEquals(2, source.opens.get())
            assertEquals(2, queue.list().size)
            withContext(Dispatchers.Main) { viewModel.setVisible(false); viewModel.restore() }
            delay(50)
            assertEquals(2, source.opens.get())
        } finally { withContext(Dispatchers.Main) { store.clear() } }
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
            it.execSQL("INSERT INTO books VALUES(2,?,'第二本','2026-01-02 03:04:05+00:00','作者/第二本 (2)',1,1)",
                arrayOf(UUID.randomUUID().toString()))
        }
        val identity = requireNotNull(metadata.importSnapshot(selection.token, fixture))
        return BookKey(identity.id, 1, sourceUuid)
    }

    private fun coordinator(source: Source) = TaskCoordinator(queue,
        listOf(CoverTaskHandler(state, metadata, covers, source, source, Dispatchers.IO)))

    private suspend fun submit() = (queue.submit(TaskSubmission(TaskRequest.CoverLoad(book),
        TaskOrigin.VISIBLE_COVER)) as SubmissionResult.Created).taskId

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

    private class Source(private val bytes: ByteArray) : CoverSource, FormatSource {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val paths = mutableListOf<String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var block = false
        var changeVersionOnOpen = false
        var failure: StorageErrorKind? = null
        var onOpen: () -> Unit = {}
        private var version = "initial"
        override suspend fun version(location: LibraryLocation, path: RelativeSourcePath): FileVersion {
            failure?.let { throw FormatSourceFailure(StorageError(it)) }
            return FileVersion(location.backend, version)
        }
        override suspend fun open(location: LibraryLocation, path: RelativeSourcePath): InputStream {
            opens.incrementAndGet()
            onOpen()
            paths.add(path.value)
            if (changeVersionOnOpen) version = "changed"
            val shouldBlock = block.also { block = false }
            return object : ByteArrayInputStream(bytes) {
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
