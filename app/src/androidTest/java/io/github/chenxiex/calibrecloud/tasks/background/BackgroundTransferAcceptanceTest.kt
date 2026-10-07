package io.github.chenxiex.calibrecloud.tasks.background

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.SourceFileLocator
import io.github.chenxiex.calibrecloud.storage.api.CopyReadResult
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskStage
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real production worker/SAF pause and resume; no Activity driver, fault injection or source writes. */
@RunWith(AndroidJUnit4::class)
class BackgroundTransferAcceptanceTest {
    @Test
    fun realWorkerPausesDurableTransferAndResumesToExactCompleteCopy() = runBlocking<Unit> {
        assumeTrue("Requires the dedicated step 09 read-only acceptance setup",
            InstrumentationRegistry.getArguments().getString("step09ReadOnly") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("io.github.chenxiex.calibrecloud.debug", context.packageName)
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val queue = dependencies.taskQueue
        val selected = requireNotNull(dependencies.state.current())
        val existingTask = InstrumentationRegistry.getArguments().getString("step09TransferTask")
            ?.let { TaskId(UUID.fromString(it)) }
        lateinit var key: CopyKey
        lateinit var expectedSource: SourceFileLocator.Relative
        suspend fun prepare() {
            assertTrue("Only the authorized SAF fixture is eligible", selected.location is LibraryLocation.Local)
            assertTrue("Only the step 09 dedicated library-a tree is eligible",
                Uri.decode(requireNotNull(dependencies.state.localTreeUri()).toString())
                    .endsWith("calibre-step09-acceptance-20261007/library-a"))
            val imported = requireNotNull(dependencies.metadata.currentImport())
            assertEquals(selected.identity, imported.identity)
            val book = imported.metadata.books.single { it.sourceId == 1L }
            val pdf = book.formats.single { it.format == BookFormat.parse("PDF") }
            assertEquals("John Schember/Quick Start Guide (1)/Quick Start Guide - John Schember.pdf", pdf.path.value)
            assertEquals(EXPECTED_SIZE, pdf.sizeBytes)
            key = CopyKey(BookKey(imported.identity.id, book.sourceId, book.sourceUuid), pdf.format)
            expectedSource = SourceFileLocator.Relative(imported.identity.location.backend, pdf.path)
        }
        if (existingTask == null) {
            assumeTrue("Do not wait for or control unrelated unfinished tasks",
                queue.list().all { it.record.state is TaskState.Finished })
            queue.executionLock.withLock {
                assumeTrue("Do not control or dispatch unrelated unfinished tasks",
                    queue.list().all { it.record.state is TaskState.Finished })
                prepare()
            }
        } else {
            // Attaching to an explicitly named running task reads private state without taking
            // the executor lock, which the real worker retains throughout this resource workflow.
            prepare()
            assertTrue("Only the named acceptance task may be unfinished", queue.list()
                .all { it.record.state is TaskState.Finished || it.record.id == existingTask })
            val record = requireNotNull(queue.get(existingTask)) { "Named acceptance task is missing" }.record
            assertFalse("Named acceptance task must still be unfinished", record.state is TaskState.Finished)
            assertTrue("Named acceptance task must be a format copy", record.submission.request is TaskRequest.FormatCopy)
            val request = record.submission.request as TaskRequest.FormatCopy
            assertEquals(key.book.libraryId, request.libraryId)
            assertEquals(key.book, request.resource.book)
            assertEquals(key.format, request.resource.format)
            assertEquals(expectedSource, request.resource.source)
        }
        val previous = dependencies.state.find(key)
        val task = if (existingTask == null) {
            val submission = dependencies.copyService.submit(key, selected.token)
            assertTrue("A fresh explicit acceptance transfer must be created", submission is SubmissionResult.Created)
            (submission as SubmissionResult.Created).taskId
        } else {
            dependencies.backgroundTasks.wake()
            existingTask
        }
        val existingPause = existingTask != null && requireNotNull(queue.get(task)).record.state is TaskState.Paused
        if (!existingPause) {
            val observedProgress = withTimeout(600_000) {
                var completed = 0L
                while (completed == 0L) {
                    val state = requireNotNull(queue.get(task)).record.state
                    assertFalse("Transfer finished before pause could be exercised", state is TaskState.Finished)
                    val running = state as? TaskState.Running
                    if (running?.stage == TaskStage.FORMAT_TRANSFER) completed = running.progress?.completed ?: 0L
                    if (completed == 0L) delay(50)
                }
                completed
            }
            assertTrue("The real worker must transfer a nonzero prefix", observedProgress > 0)
            assertTrue("Pause must be accepted by the actual transfer handler", queue.control(task, TaskControl.PAUSE))
            withTimeout(120_000) {
                while (requireNotNull(queue.get(task)).record.state !is TaskState.Paused) {
                    assertFalse("Pause must reach a safe boundary before completion",
                        requireNotNull(queue.get(task)).record.state is TaskState.Finished)
                    delay(50)
                }
            }
        }
        val paused = requireNotNull(queue.get(task))
        assertEquals(TaskState.Paused(TaskStage.FORMAT_TRANSFER), paused.record.state)
        val checkpoint = requireNotNull(paused.checkpoint)
        val directory = File(context.filesDir, "book-staging/${task.value}")
        val staged = File(directory, "${checkpoint.generation}.part")
        val evidence = JSONObject(File(directory, "${checkpoint.generation}.resume").readText())
        val durableOffset = evidence.getLong("offset")
        assertTrue("Paused transfer must retain a durable nonzero prefix", durableOffset > 0)
        assertTrue(durableOffset <= EXPECTED_SIZE)
        assertTrue(staged.isFile)
        val pausedLength = staged.length()
        // After a real process interruption, staging may retain an unconfirmed tail beyond the
        // fsynced prefix. Recovery checks the prefix then truncates that tail before resuming;
        // pausing during the prefix check does not require truncation to have happened already.
        assertTrue("Paused staging must contain the complete durable prefix", pausedLength >= durableOffset)
        assertEquals("Pause must not publish a new complete copy", previous, dependencies.state.find(key))
        repeat(3) {
            delay(1_000)
            assertEquals(TaskState.Paused(TaskStage.FORMAT_TRANSFER), queue.get(task)?.record?.state)
            assertEquals("Paused staging must not keep growing", pausedLength, staged.length())
            assertEquals("Pause must preserve the previous complete manifest", previous, dependencies.state.find(key))
        }
        assertTrue("Resume must be accepted by the actual paused task", queue.control(task, TaskControl.RESUME))
        withTimeout(900_000) {
            while (requireNotNull(queue.get(task)).record.state !is TaskState.Finished) delay(100)
        }
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)?.record?.state)
        assertFalse("The retained same-version prefix must not require a full restart", queue.get(task)!!.record.restartedTransfer)
        val readable = dependencies.copyReader.read(key)
        assertTrue("Only a complete private copy is readable", readable is CopyReadResult.Available)
        val digest = MessageDigest.getInstance("SHA-256")
        val actualSize = withContext(Dispatchers.IO) {
            (readable as CopyReadResult.Available).handle.use { handle ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = handle.input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    assertTrue("The copied fixture exceeds its expected size", size <= EXPECTED_SIZE)
                    digest.update(buffer, 0, count)
                }
                size
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(EXPECTED_SIZE, actualSize)
        assertEquals(EXPECTED_SHA, actualHash)
        assertFalse("Successful publication removes private task staging", directory.exists())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("step09Transfer", if (existingPause)
                "production WorkManager/SAF; existing durable pause verified; pause stable 3s; resume completed"
                else "production WorkManager/SAF; nonzero progress; pause stable 3s; resume completed")
            putLong("step09PausedBytes", durableOffset)
            putLong("step09CopyBytes", actualSize)
            putString("step09CopySHA256", actualHash)
        })
    }

    companion object {
        private const val EXPECTED_SIZE = 268_436_064L
        private const val EXPECTED_SHA = "df5090b32a623aa8494529236ec42d5f1a5463850607c10d9d00afb178e71e9d"
    }
}
