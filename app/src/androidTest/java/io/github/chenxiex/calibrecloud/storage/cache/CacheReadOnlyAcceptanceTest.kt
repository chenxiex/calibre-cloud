package io.github.chenxiex.calibrecloud.storage.cache

import io.github.chenxiex.calibrecloud.storage.api.of
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.ApplicationDependencies
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.CopyReadResult
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.storage.local.AndroidLocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceResult
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
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

/**
 * Opt-in production SAF/Graph service integration, not UI acceptance. The selected library-a source
 * is read only; all copy/cover publication and cleanup use the debug application's private state.
 * Preparation imports the known two-format fixture and finishes other tasks before this probe.
 */
@RunWith(AndroidJUnit4::class)
class CacheReadOnlyAcceptanceTest {
    @Before
    fun requireExplicitOptIn() {
        assumeTrue("Production cache acceptance requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("step08ReadOnly") == "true")
        assertEquals("Only the independent debug application is eligible",
            "io.github.chenxiex.calibrecloud.debug",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    @Test
    fun realBackendSourceRemainsIntactAcrossScopedPrivateCleanup() = runBlocking<Unit> {
        withTimeout(600_000) { runAcceptance() }
    }

    private suspend fun runAcceptance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val queue = dependencies.taskQueue
        val selected = requireNotNull(dependencies.state.current())
        val imported = requireNotNull(dependencies.metadata.currentImport()) {
            "Import the dedicated library-a fixture before running acceptance"
        }
        assertEquals(imported.identity, selected.identity)
        val location = imported.identity.location
        val book = imported.metadata.books.single { it.sourceId == 1L }
        assertEquals("John Schember/Quick Start Guide (1)", book.path.value)
        val bookKey = BookKey(imported.identity.id, book.sourceId, book.sourceUuid)
        val epub = CopyKey(bookKey, BookFormat.parse("EPUB"))
        val pdf = CopyKey(bookKey, BookFormat.parse("PDF"))
        assertEquals(EPUB_PATH, book.formats.single { it.format == epub.format }.path)
        assertEquals(PDF_PATH, book.formats.single { it.format == pdf.format }.path)
        val preferences = preferences(dependencies, imported.identity.id)
        val sources = mutableMapOf<RelativeSourcePath, SourceEvidence>()

        queue.executionLock.withLock {
            assertIdle(dependencies)
            when (location) {
                is LibraryLocation.Local -> {
                    val tree = requireNotNull(dependencies.state.localTreeUri())
                    assertEquals("Only the prepared test library is eligible", "library-a",
                        AndroidLocalDocumentAccess(context).root(tree).name)
                }
                is LibraryLocation.OneDrive -> {
                    val root = dependencies.oneDriveBackend.listAllDirectories(location)
                    assertTrue("Cannot verify the prepared test root", root is OneDriveSourceResult.Available)
                    assertEquals("library-a", (root as OneDriveSourceResult.Available).value.parentName)
                }
            }
            sources[EPUB_PATH] = sourceEvidence(dependencies, location, EPUB_PATH)
            sources[PDF_PATH] = sourceEvidence(dependencies, location, PDF_PATH)
            sources[DATABASE_PATH] = sourceEvidence(dependencies, location, DATABASE_PATH)
            assertEquals(51_734, sources.getValue(EPUB_PATH).bytes.size)
            assertEquals(EPUB_SHA, hash(sources.getValue(EPUB_PATH).bytes))
            assertEquals(608, sources.getValue(PDF_PATH).bytes.size)
            assertEquals(PDF_SHA, hash(sources.getValue(PDF_PATH).bytes))
        }

        for (key in listOf(epub, pdf)) {
            assertIdle(dependencies)
            assertEquals(selected, dependencies.state.current())
            val task = created(dependencies.copyService.submit(key, selected.token))
            awaitOnly(dependencies, task)
            assertArrayEquals(sources.getValue(if (key == epub) EPUB_PATH else PDF_PATH).bytes,
                copyBytes(dependencies, key))
        }
        if (book.hasCover) {
            assertIdle(dependencies)
            val task = created(dependencies.coverService.submit(bookKey, selected.token))
            awaitOnly(dependencies, task)
            requireNotNull(dependencies.covers.read(bookKey)) { "Real cover task must publish a readable cache" }.recycle()
        }

        val pdfBefore = requireNotNull(dependencies.state.find(pdf))
        val remove = requireNotNull(dependencies.maintenance.previewCopies(setOf(bookKey), setOf(epub.format)))
        assertEquals(setOf(epub), remove.copies)
        assertTrue("Exact EPUB cleanup must finish", dependencies.maintenance.execute(remove))
        assertNull(dependencies.state.find(epub))
        assertEquals(CopyReadResult.Missing, dependencies.copyReader.read(epub))
        assertEquals("The PDF manifest must survive EPUB removal", pdfBefore, dependencies.state.find(pdf))
        assertArrayEquals(sources.getValue(PDF_PATH).bytes, copyBytes(dependencies, pdf))
        assertNotNull("Removing one format must retain metadata", dependencies.metadata.currentImport())
        queue.executionLock.withLock {
            assertIdle(dependencies)
            verifySources(dependencies, location, sources)
        }

        val metadataCleanup = requireNotNull(dependencies.maintenance.previewMetadata())
        assertEquals(setOf(imported.identity.id), metadataCleanup.libraries)
        assertTrue("Current metadata cleanup must finish", dependencies.maintenance.execute(metadataCleanup))
        assertNull("Complete metadata must become unavailable", dependencies.metadata.currentImport())
        assertNull("Cover cache must become unavailable", dependencies.covers.read(bookKey))
        assertEquals("Current library configuration must remain", selected, dependencies.state.current())
        assertEquals("Custom read-column preferences must remain", preferences,
            preferences(dependencies, imported.identity.id))
        val manifest = dependencies.state.listCopies(imported.identity.id, 100, 0)
        assertEquals(setOf(pdf), manifest.map { it.key }.toSet())
        assertEquals(SourceAvailability.UNCONFIRMED, manifest.single().sourceAvailability)
        assertEquals(pdfBefore.location, manifest.single().location)
        assertArrayEquals(sources.getValue(PDF_PATH).bytes, copyBytes(dependencies, pdf))
        assertEquals(CopyReadResult.Missing, dependencies.copyReader.read(epub))

        queue.executionLock.withLock {
            assertIdle(dependencies)
            verifySources(dependencies, location, sources)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("step08Integration", "production WorkManager read-only service integration; no UI interactions")
            putString("step08Backend", location.backend.toString())
            putString("step08EPUBSHA256", hash(sources.getValue(EPUB_PATH).bytes))
            putString("step08PDFSHA256", hash(sources.getValue(PDF_PATH).bytes))
            putString("step08SourceDatabaseSHA256", hash(sources.getValue(DATABASE_PATH).bytes))
            putString("step08Cleanup", "EPUB removed; PDF retained; metadata and cover unavailable; source bytes and versions unchanged")
            putString("step08Cover", if (book.hasCover) "real cover published then cleared" else "fixture has no cover")
        })
    }

    private suspend fun assertIdle(dependencies: ApplicationDependencies) {
        assertTrue("Finish other app tasks before this dedicated integration probe",
            dependencies.taskQueue.list().all { it.record.state is TaskState.Finished })
    }

    private fun created(result: SubmissionResult): TaskId {
        assertTrue("Only a fresh explicit acceptance task is eligible", result is SubmissionResult.Created)
        return (result as SubmissionResult.Created).taskId
    }

    private suspend fun awaitOnly(dependencies: ApplicationDependencies, task: TaskId) {
        // Submission already wakes the real production worker. It may complete before the first
        // observation; an empty unfinished set is valid, but an unrelated task is never eligible.
        while (true) {
            assertTrue("Do not execute unrelated unfinished tasks", dependencies.taskQueue.list()
                .filter { it.record.state !is TaskState.Finished }.all { it.record.id == task })
            val state = requireNotNull(dependencies.taskQueue.get(task)).record.state
            if (state is TaskState.Finished) break
            delay(100)
        }
        assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(task)?.record?.state)
        assertIdle(dependencies)
    }

    private suspend fun copyBytes(dependencies: ApplicationDependencies, key: CopyKey): ByteArray {
        val result = dependencies.copyReader.read(key)
        assertTrue("A retained copy must be readable without source fallback", result is CopyReadResult.Available)
        return (result as CopyReadResult.Available).handle.use { bounded(it.input) }
    }

    private suspend fun sourceEvidence(dependencies: ApplicationDependencies, location: LibraryLocation,
        path: RelativeSourcePath): SourceEvidence {
        val version = dependencies.librarySources.of(location).lookup(location, path) {}.version
        val bytes = dependencies.librarySources.of(location).lookup(location, path) {}.open().use { bounded(it) }
        assertEquals("Source must remain stable throughout a read-only probe", version,
            dependencies.librarySources.of(location).lookup(location, path) {}.version)
        return SourceEvidence(version, bytes)
    }

    private suspend fun verifySources(dependencies: ApplicationDependencies, location: LibraryLocation,
        expected: Map<RelativeSourcePath, SourceEvidence>) {
        expected.forEach { (path, before) ->
            val after = sourceEvidence(dependencies, location, path)
            assertEquals("Private cleanup must leave the source version intact", before.version, after.version)
            assertArrayEquals("Private cleanup must leave every source byte intact", before.bytes, after.bytes)
            assertEquals(hash(before.bytes), hash(after.bytes))
        }
    }

    private fun preferences(dependencies: ApplicationDependencies, library: LibraryId): Pair<Long?, String?> =
        dependencies.database.readableDatabase.rawQuery(
            "SELECT read_column_id,read_column_lookup FROM library_preferences WHERE library_id = ?",
            arrayOf(library.value.toString())).use {
            assertTrue("Library preferences must be durable", it.moveToFirst())
            (if (it.isNull(0)) null else it.getLong(0)) to (if (it.isNull(1)) null else it.getString(1))
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
            assertTrue("Prepared acceptance fixture exceeds the read bound", output.size().toLong() + count <= 32L * 1024 * 1024)
            output.write(buffer, 0, count)
        }
    }

    private data class SourceEvidence(val version: FileVersion, val bytes: ByteArray)

    companion object {
        private val EPUB_PATH = RelativeSourcePath("John Schember/Quick Start Guide (1)/Quick Start Guide - John Schember.epub")
        private val PDF_PATH = RelativeSourcePath("John Schember/Quick Start Guide (1)/Quick Start Guide - John Schember.pdf")
        private val DATABASE_PATH = RelativeSourcePath("metadata.db")
        private const val EPUB_SHA = "ba999028397cc788894686459a59e196299e123d74617ca501218afa456b15b5"
        private const val PDF_SHA = "634540ec54cc3a5ffd6698d5673134e85f46e4b4cd34549d3ed001190039e888"
    }
}
