package io.github.chenxiex.calibrecloud.tasks.copies

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceResult
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationSession
import io.github.chenxiex.calibrecloud.storage.local.AndroidSnapshotValidator
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceBackend
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveLibrarySource
import io.github.chenxiex.calibrecloud.tasks.covers.CoverTaskHandler
import io.github.chenxiex.calibrecloud.tasks.sync.LibrarySyncTaskHandler
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Explicitly authorized read-only cloud acceptance; writes only the debug app's private copy state. */
@RunWith(AndroidJUnit4::class)
class OneDriveReadOnlyAcceptanceTest {
    @Before
    fun requireExplicitOptIn() {
        assumeTrue("Real cloud acceptance requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("step06ReadOnly") == "true")
        assertTrue("Acceptance requires the independent debug application",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName ==
                "io.github.chenxiex.calibrecloud.debug")
    }

    @Test
    fun realCloudRangeAndInterruptedTransferRecoverExactBytes() = runBlocking<Unit> {
        withTimeout(600_000) { runAcceptance() }
    }

    /**
     * Step 05 request targets against the real service. The production backend configuration is
     * rebuilt with a test-side counter on both clients; the counter keeps only method, endpoint
     * class and status, never URLs, paths or tokens. Results are logged under Step05Acceptance.
     */
    @Test
    fun realCloudRequestCountsMeetStep05Targets() = runBlocking<Unit> {
        withTimeout(600_000) { runRequestCounts() }
    }

    private class RequestCounter : Interceptor {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, Int>>())
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            val endpoint = when {
                url.host != "graph.microsoft.com" -> "download"
                url.pathSegments.any { it.endsWith(":") } -> "items-path"
                url.encodedPath.endsWith("/children") -> "children"
                url.encodedPath.endsWith("/thumbnails") -> "thumbnails"
                url.encodedPath.endsWith("/content") -> "content"
                url.pathSegments.getOrNull(1) == "me" -> "me"
                else -> "items-id"
            }
            val response = chain.proceed(request)
            calls.add(Triple(request.method, endpoint, response.code))
            return response
        }
        fun count(endpoint: String) = calls.count { it.second == endpoint }
        fun graph() = calls.count { it.second != "download" }
        fun summary() = calls.groupingBy { "${it.first}:${it.second}:${it.third}" }.eachCount().toSortedMap().toString()
    }

    private suspend fun runRequestCounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val queue = dependencies.taskQueue
        val imported = requireNotNull(dependencies.metadata.currentImport()) { "Import the dedicated acceptance library first" }
        val location = imported.identity.location as? LibraryLocation.OneDrive ?: error("Select the dedicated OneDrive acceptance library")
        val authorization = dependencies.oneDriveAuthorization
        val counter = RequestCounter()
        suspend fun session() = kotlinx.coroutines.currentCoroutineContext()[OneDriveAuthorizationSession]?.id
        val backend = OneDriveSourceBackend({ force -> authorization.backendAccessToken(force, session()) },
            File(context.filesDir, "snapshots/onedrive"), AndroidSnapshotValidator(), Dispatchers.IO,
            client = OkHttpClient.Builder().addInterceptor(counter).build(),
            contentClient = OkHttpClient.Builder().addInterceptor(counter).build(),
            accountProvider = { authorization.accountSubject(session()) })
        val oneDrive = OneDriveLibrarySource(backend)
        val sources = LibrarySources { if (it == BackendKind.ONEDRIVE) oneDrive else dependencies.librarySources.of(it) }
        val coordinator = TaskCoordinator(queue, listOf(
            LibrarySyncTaskHandler(dependencies.state, sources, dependencies.libraryAuthorizations, dependencies.metadata, Dispatchers.IO),
            FormatCopyTaskHandler(dependencies.state, dependencies.metadata, queue, sources, context.filesDir, Dispatchers.IO),
            CoverTaskHandler(dependencies.state, dependencies.metadata, dependencies.covers, sources, Dispatchers.IO)))
        val productionWake = queue.onWake
        queue.onWake = {}
        try {
            val manager = WorkManager.getInstance(context)
            withTimeout(60_000) {
                while (withContext(Dispatchers.IO) {
                    manager.getWorkInfosByTag(io.github.chenxiex.calibrecloud.tasks.background.BackgroundTasks.QUEUE_TAG)
                        .get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
                }) delay(100)
            }
            queue.executionLock.withLock {
                assertTrue("Finish other private tasks before counting", queue.list().all { it.record.state is TaskState.Finished })
            }
            suspend fun phase(name: String, block: suspend () -> Unit): Long {
                counter.calls.clear()
                val started = System.nanoTime()
                block()
                val millis = (System.nanoTime() - started) / 1_000_000
                android.util.Log.i("Step05Acceptance", "phase=$name ms=$millis graph=${counter.graph()} calls=${counter.summary()}")
                assertEquals("$name must not list children", 0, counter.count("children"))
                assertTrue("$name request failed: ${counter.summary()}", counter.calls.all { it.third in 200..399 || it.second == "items-path" })
                return millis
            }
            fun completed(task: TaskId?) = runBlocking { queue.get(requireNotNull(task))!!.record.state } == TaskState.Finished(TaskResult.Completed)

            // A sync after an import made before step 05 may read once; the next one must stop after one request.
            phase("sync_first") { val task = dependencies.librarySync.request(TaskOrigin.MANUAL_SYNC); coordinator.drain(); assertTrue(completed(task)) }
            phase("sync_unchanged") {
                val task = dependencies.librarySync.request(TaskOrigin.MANUAL_SYNC)
                coordinator.drain()
                assertTrue(completed(task))
                assertEquals("An unchanged database is one Graph request", 1, counter.graph())
                assertEquals(1, counter.count("items-path"))
                assertEquals(0, counter.count("download"))
            }
            val current = requireNotNull(dependencies.metadata.currentImport())
            val book = current.metadata.books.single { it.sourceId == 1L }
            val format = book.formats.single { it.format == BookFormat.parse("EPUB") }
            assertEquals(PATH, format.path)
            val key = CopyKey(BookKey(current.identity.id, book.sourceId, book.sourceUuid), format.format)
            phase("download") {
                val task = (coordinator.submit(TaskSubmission(TaskRequest.FormatCopy(FormatResource(key.book, key.format,
                    SourceFileLocator.Relative(BackendKind.ONEDRIVE, PATH))), TaskOrigin.USER_DOWNLOAD)) as SubmissionResult.Created).taskId
                coordinator.drain()
                assertTrue(completed(task))
                assertEquals(1, counter.count("items-path"))
                assertTrue("At most a /content fallback follows the lookup", counter.graph() <= 2)
                android.util.Log.i("Step05Acceptance", "download_url_returned=${counter.count("content") == 0}")
            }
            val copy = dependencies.copyReader.read(key) as CopyReadResult.Available
            copy.handle.use { assertEquals(EXPECTED_SHA, hash(readBounded(it.input, EXPECTED_SIZE.toInt()))) }
            if (book.hasCover) phase("cover") {
                val task = (coordinator.submit(TaskSubmission(TaskRequest.CoverLoad(key.book), TaskOrigin.VISIBLE_COVER))
                    as? SubmissionResult.Created)?.taskId
                coordinator.drain()
                assertTrue(completed(task))
                assertEquals("A cover is one Graph request", 1, counter.graph())
            }
            phase("open_existing") {
                val opened = dependencies.copyReader.read(key) as CopyReadResult.Available
                opened.handle.close()
                assertEquals(0, counter.calls.size)
            }
        } finally {
            queue.onWake = productionWake
            productionWake()
        }
    }

    private suspend fun runAcceptance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val queue = dependencies.taskQueue
        val source = dependencies.librarySources.of(BackendKind.ONEDRIVE)
        val imported = requireNotNull(dependencies.metadata.currentImport()) {
            "Import the dedicated acceptance library before running acceptance"
        }
        val location = imported.identity.location as? LibraryLocation.OneDrive
            ?: error("Select the dedicated OneDrive acceptance library")
        lateinit var key: CopyKey
        lateinit var expected: ByteArray
        lateinit var version: FileVersion
        // Use the process-owned queue lock even for ordinary source probes.
        queue.executionLock.withLock {
            assertTrue("Finish other private tasks before acceptance",
                queue.list().all { it.record.state is TaskState.Finished })
            assertTrue("The imported library must remain selected",
                dependencies.state.current()?.identity == imported.identity)
            val directories = dependencies.oneDriveBackend.listAllDirectories(location)
            assertTrue("Cannot verify the dedicated acceptance root",
                directories is OneDriveSourceResult.Available)
            assertTrue("Only the dedicated library-a root is eligible",
                (directories as OneDriveSourceResult.Available).value.parentName == "library-a")
            val book = imported.metadata.books.singleOrNull { it.sourceId == 1L }
                ?: error("Dedicated fixture book is missing")
            val format = book.formats.singleOrNull { it.format == BookFormat.parse("EPUB") }
                ?: error("Dedicated fixture EPUB is missing")
            assertTrue("Unexpected fixture source path", format.path == PATH)
            key = CopyKey(BookKey(imported.identity.id, book.sourceId, book.sourceUuid), format.format)
            val found = source.lookup(location, PATH) {}
            version = found.version
            assertEquals("Unexpected fixture byte length", EXPECTED_SIZE, found.size)
            expected = found.open().use { readBounded(it, EXPECTED_SIZE.toInt()) }
            assertEquals("Incomplete fixture response", EXPECTED_SIZE, expected.size.toLong())
            assertEquals("Cloud fixture bytes differ from the prepared fixture", EXPECTED_SHA, hash(expected))
            val ranged = found.openRange(4096L)
            assertNotNull("The real download endpoint must support validated nonzero ranges", ranged)
            val suffix = ranged!!.use { readBounded(it, EXPECTED_SIZE.toInt() - 4096) }
            assertArrayEquals("Real range response differs from the full response",
                expected.copyOfRange(4096, expected.size), suffix)
            assertTrue("Source version changed during the read-only probe",
                version == source.lookup(location, PATH) {}.version)
        }

        // This path deliberately injects a transport interruption and retry clock; it is not a
        // WorkManager execution check. Quiesce already-enqueued workers before submitting the
        // fixture task, so the production handler cannot consume it between the two test drains.
        val productionWake = queue.onWake
        queue.onWake = {}
        try {
            val manager = WorkManager.getInstance(context)
            withTimeout(60_000) {
                while (withContext(Dispatchers.IO) {
                    manager.getWorkInfosByTag(io.github.chenxiex.calibrecloud.tasks.background.BackgroundTasks.QUEUE_TAG)
                        .get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
                }) delay(100)
            }
            queue.executionLock.withLock {
                assertTrue("Finish other private tasks before injected recovery",
                    queue.list().all { it.record.state is TaskState.Finished })
            }
            val interrupted = InterruptOnce(source)
            val handler = FormatCopyTaskHandler(dependencies.state, dependencies.metadata, queue,
                LibrarySources { interrupted }, context.filesDir, Dispatchers.IO)
            var clock = 0L
            // Both this test coordinator and the app coordinator use the same queue execution lock.
            val coordinator = TaskCoordinator(queue, listOf(handler), now = { clock })
            val submission = coordinator.submit(TaskSubmission(TaskRequest.FormatCopy(
                FormatResource(key.book, key.format, SourceFileLocator.Relative(location.backend, PATH)), version),
                TaskOrigin.USER_DOWNLOAD))
            assertTrue("A fresh acceptance task must be created", submission is SubmissionResult.Created)
            val task = (submission as SubmissionResult.Created).taskId
            val previous = dependencies.state.find(key)
            coordinator.drain()
            val waiting = requireNotNull(queue.get(task)) { "Acceptance task disappeared" }
            assertTrue("Injected transient interruption must wait for retry", waiting.record.state is TaskState.Waiting)
            assertTrue("Unfinished transfer must preserve the previous complete copy", previous == dependencies.state.find(key))
            assertEquals("Only one full stream should be opened", 1, interrupted.fullOpens)
            val checkpoint = requireNotNull(waiting.checkpoint) { "Durable checkpoint is missing" }
            val staging = File(context.filesDir, "book-staging/${task.value}/${checkpoint.generation}.part")
            val evidence = JSONObject(File(staging.parentFile, "${checkpoint.generation}.resume").readText())
            assertEquals("Checkpoint must persist the nonzero durable prefix", INTERRUPT_AT, evidence.getLong("offset"))
            assertArrayEquals("Staged prefix differs from the actual cloud bytes",
                expected.copyOfRange(0, INTERRUPT_AT.toInt()), staging.inputStream().use { readBounded(it, INTERRUPT_AT.toInt()) })
            // Advance only the injected retry clock, keeping Wi-Fi and ADB connected.
            clock = waiting.retryAt
            coordinator.drain()
            assertTrue("Recovered production transfer must finish successfully",
                queue.get(task)?.record?.state == TaskState.Finished(TaskResult.Completed))
            assertEquals("Recovery must request the saved nonzero range", listOf(INTERRUPT_AT), interrupted.rangeOffsets)
            assertEquals("Recovery must not open a second full stream", 1, interrupted.fullOpens)
            val copy = dependencies.copyReader.read(key)
            assertTrue("Only a complete private copy may become readable", copy is CopyReadResult.Available)
            (copy as CopyReadResult.Available).handle.use {
                assertArrayEquals("Recovered private copy must match all original cloud bytes", expected,
                    readBounded(it.input, EXPECTED_SIZE.toInt()))
            }
            queue.executionLock.withLock {
                assertTrue("Source version changed during recovery", version == source.lookup(location, PATH) {}.version)
            }
            assertFalse("Successful publication must remove the task staging directory", staging.parentFile!!.exists())
        } finally {
            queue.onWake = productionWake
            productionWake()
        }
    }

    /** Deterministic fault at the backend seam; actual content and resumed ranges remain production reads. */
    private class InterruptOnce(private val delegate: LibrarySource) : LibrarySource by delegate {
        var fullOpens = 0
        val rangeOffsets = mutableListOf<Long>()

        override suspend fun lookup(location: LibraryLocation, path: RelativeSourcePath, control: suspend () -> Unit): SourceFile {
            val found = delegate.lookup(location, path, control)
            return object : SourceFile by found {
                override suspend fun open() = interrupt(found.open())
                override suspend fun openRange(offset: Long): InputStream? {
                    rangeOffsets.add(offset)
                    return found.openRange(offset)
                }
            }
        }

        private fun interrupt(input: InputStream): InputStream {
            fullOpens++
            if (fullOpens != 1) return input
            return object : FilterInputStream(input) {
                private var delivered = 0L
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (delivered >= INTERRUPT_AT)
                        throw SourceFailure(StorageError(StorageErrorKind.NO_NETWORK), true)
                    val count = `in`.read(buffer, offset, minOf(length.toLong(), INTERRUPT_AT - delivered).toInt())
                    if (count > 0) delivered += count
                    return count
                }
            }
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(limit)
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) return output.toByteArray()
            if (count == 0) continue
            assertTrue("Source response exceeds the prepared fixture length", output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
    }

    companion object {
        private val PATH = RelativeSourcePath("John Schember/Quick Start Guide (1)/Quick Start Guide - John Schember.epub")
        private const val EXPECTED_SIZE = 51734L
        private const val EXPECTED_SHA = "ba999028397cc788894686459a59e196299e123d74617ca501218afa456b15b5"
        private const val INTERRUPT_AT = 8192L
    }
}
