package io.github.chenxiex.calibrecloud.tasks.copies

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceResult
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
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

    private suspend fun runAcceptance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val queue = dependencies.taskQueue
        val source = dependencies.formatSource
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
            val directories = dependencies.oneDriveBackend.listDirectories(location)
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
            version = source.version(location, PATH)
            assertEquals("Unexpected fixture byte length", EXPECTED_SIZE, source.size(location, PATH))
            expected = source.open(location, PATH).use { readBounded(it, EXPECTED_SIZE.toInt()) }
            assertEquals("Incomplete fixture response", EXPECTED_SIZE, expected.size.toLong())
            assertEquals("Cloud fixture bytes differ from the prepared fixture", EXPECTED_SHA, hash(expected))
            val ranged = source.openRange(location, PATH, 4096L, version)
            assertNotNull("The real download endpoint must support validated nonzero ranges", ranged)
            val suffix = ranged!!.use { readBounded(it, EXPECTED_SIZE.toInt() - 4096) }
            assertArrayEquals("Real range response differs from the full response",
                expected.copyOfRange(4096, expected.size), suffix)
            assertTrue("Source version changed during the read-only probe",
                version == source.version(location, PATH))
        }

        val interrupted = InterruptOnce(source)
        val handler = FormatCopyTaskHandler(dependencies.state, dependencies.metadata, queue,
            interrupted, context.filesDir, Dispatchers.IO)
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
            assertTrue("Source version changed during recovery", version == source.version(location, PATH))
        }
        assertFalse("Successful publication must remove the task staging directory", staging.parentFile!!.exists())
    }

    /** Deterministic fault at the backend seam; actual content and resumed ranges remain production reads. */
    private class InterruptOnce(private val delegate: FormatSource) : FormatSource by delegate {
        var fullOpens = 0
        val rangeOffsets = mutableListOf<Long>()

        override suspend fun open(location: LibraryLocation, path: RelativeSourcePath): InputStream {
            fullOpens++
            val input = delegate.open(location, path)
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
                        throw FormatSourceFailure(StorageError(StorageErrorKind.NO_NETWORK), true)
                    val count = `in`.read(buffer, offset, minOf(length.toLong(), INTERRUPT_AT - delivered).toInt())
                    if (count > 0) delivered += count
                    return count
                }
            }
        }

        override suspend fun openRange(location: LibraryLocation, path: RelativeSourcePath,
            offset: Long, expectedVersion: FileVersion): InputStream? {
            rangeOffsets.add(offset)
            return delegate.openRange(location, path, offset, expectedVersion)
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
