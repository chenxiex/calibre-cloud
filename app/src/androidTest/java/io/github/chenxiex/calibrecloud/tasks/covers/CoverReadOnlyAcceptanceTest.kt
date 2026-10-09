package io.github.chenxiex.calibrecloud.tasks.covers

import io.github.chenxiex.calibrecloud.storage.api.of
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.storage.local.AndroidLocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceResult
import io.github.chenxiex.calibrecloud.tasks.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Opt-in production SAF/Graph reads from the prepared library-a; all writes are private debug cache. */
@RunWith(AndroidJUnit4::class)
class CoverReadOnlyAcceptanceTest {
    @Before
    fun requireExplicitOptIn() {
        assumeTrue("Real cover acceptance requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("step07ReadOnly") == "true")
        assertEquals("io.github.chenxiex.calibrecloud.debug",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test
    fun selectedRealBackendDecodesSourceAndPublishesOnlyAnExplicitCoverTask() = runBlocking<Unit> {
        withTimeout(600_000) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
            val queue = dependencies.taskQueue
            val imported = requireNotNull(dependencies.metadata.currentImport()) {
                "Import the dedicated library-a test copy before enabling acceptance"
            }
            val location = imported.identity.location
            val book = imported.metadata.books.single { it.sourceId == 1L }
            assertTrue(book.hasCover)
            val path = RelativeSourcePath("${book.path.value}/cover.jpg")
            assertEquals("John Schember/Quick Start Guide (1)/cover.jpg", path.value)
            val key = BookKey(imported.identity.id, book.sourceId, book.sourceUuid)
            var thumbnail: Pair<Int, Int>? = null
            var responseDimensions: Pair<Int, Int>? = null
            var responseBytes = 0
            var responseHash = ""
            var originalHash = ""
            queue.executionLock.withLock {
                assertTrue("Complete other app tasks before this dedicated probe",
                    queue.list().all { it.record.state is TaskState.Finished })
                assertEquals(imported.identity, dependencies.state.current()?.identity)
                when (location) {
                    is LibraryLocation.Local -> {
                        val tree = requireNotNull(dependencies.state.accessKey(location))
                        assertEquals("Only the prepared test library is eligible", "library-a",
                            AndroidLocalDocumentAccess(context).root(tree).name)
                    }
                    is LibraryLocation.OneDrive -> {
                        val root = dependencies.oneDriveBackend.listAllDirectories(location)
                        assertTrue(root is OneDriveSourceResult.Available)
                        assertEquals("library-a", (root as OneDriveSourceResult.Available).value.parentName)
                    }
                }
                val before = dependencies.librarySources.of(location).lookup(location, path) {}.version
                val original = dependencies.librarySources.of(location).lookup(location, path) {}.open().use { bounded(it) }
                originalHash = hash(original)
                assertEquals("Prepared fixture cover bytes differ", 36_903, original.size)
                assertEquals("Prepared fixture cover content differs",
                    "a559f3c85079c5e1389db820df0579035bd5e821f4d29a9f395dbf4f23c58615", originalHash)
                val input = if (location is LibraryLocation.OneDrive) {
                    val result = dependencies.oneDriveBackend.openCover(location, path,
                        CoverRepository.WIDTH, CoverRepository.HEIGHT) { width, height -> thumbnail = width to height }
                    assertTrue("Real thumbnail/original cover read failed", result is OneDriveSourceResult.Available)
                    (result as OneDriveSourceResult.Available).value.stream
                } else dependencies.librarySources.of(location).openCover(location, path, CoverRepository.WIDTH, CoverRepository.HEIGHT) {}.input
                val bytes = input.use { bounded(it) }
                responseBytes = bytes.size
                responseHash = hash(bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                assertTrue("Prepared fixture cover has unexpected dimensions",
                    bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                responseDimensions = bounds.outWidth to bounds.outHeight
                // Real personal-drive descriptors can report the bounding box (for example
                // 800x800) while the aspect-preserving response is 600x800. Record both; the
                // production decoder always uses the actual response dimensions.
                thumbnail?.let {
                    assertTrue("Actual thumbnail must remain inside the advertised bounds",
                        bounds.outWidth <= it.first && bounds.outHeight <= it.second)
                }
                val sampled = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > CoverRepository.WIDTH * 2 ||
                        bounds.outHeight / inSampleSize > CoverRepository.HEIGHT * 2) inSampleSize *= 2
                }
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled)) {
                    "Real source response must decode as an image"
                }
                try { assertTrue(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
                assertEquals("Read-only probe must not change the source", before, dependencies.librarySources.of(location).lookup(location, path) {}.version)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("step07CoverBackend", location.backend.toString())
                putString("step07OriginalSHA256", originalHash)
                putString("step07ResponseSHA256", responseHash)
                putInt("step07ResponseBytes", responseBytes)
                putString("step07ResponseDimensions", responseDimensions?.let { "${it.first}x${it.second}" })
                putString("step07CoverSource", thumbnail?.let { "thumbnail ${it.first}x${it.second}" }
                    ?: if (location is LibraryLocation.OneDrive) "original fallback" else "SAF original")
            })
            val submission = dependencies.coverService.submit(key, requireNotNull(dependencies.state.current()).token)
            assertTrue("Acceptance needs a fresh explicit cover task", submission is SubmissionResult.Created)
            val task = (submission as SubmissionResult.Created).taskId
            // The submit hook schedules the production WorkManager driver. Fast completion before
            // this observation is valid; no foreground drain substitutes for background execution.
            while (true) {
                assertTrue("Do not execute unrelated unfinished tasks", queue.list()
                    .filter { it.record.state !is TaskState.Finished }.all { it.record.id == task })
                if (requireNotNull(queue.get(task)).record.state is TaskState.Finished) break
                delay(100)
            }
            assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)?.record?.state)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("step07CoverExecution", "production WorkManager driver; real source read-only")
            })
            val readable = requireNotNull(dependencies.covers.read(key))
            try {
                assertTrue(readable.width in 1..CoverRepository.WIDTH)
                assertTrue(readable.height in 1..CoverRepository.HEIGHT)
            } finally { readable.recycle() }
            // The normal reader has no backend or queue dependency and does not create work.
            val count = queue.list().size
            requireNotNull(dependencies.covers.read(key)).recycle()
            assertEquals(count, queue.list().size)
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun bounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) continue
            assertTrue("Cover source exceeds the production bound", output.size().toLong() + count <= CoverRepository.MAX_ENCODED_BYTES)
            output.write(buffer, 0, count)
        }
    }
}
