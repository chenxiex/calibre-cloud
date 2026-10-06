package io.github.chenxiex.calibrecloud.storage.local

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSourceBackendTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Documents : LocalDocumentAccess {
        var denied = false
        var outside = false
        var reads = 0
        var onOpen: (Int) -> InputStream = { ByteArrayInputStream("database".toByteArray()) }
        var range: (String, Long) -> InputStream? = { _, _ -> null }
        override fun openRange(treeUri: String, documentId: String, offset: Long): InputStream? = range(documentId, offset)
        var log: ByteArray? = null
        val root = LocalDocument("root", "library", true, false)
        override fun root(treeUri: String): LocalDocument {
            if (denied) throw SecurityException()
            return root
        }
        override fun children(treeUri: String, parentId: String): List<LocalDocument> = when (parentId) {
            "root" -> listOf(LocalDocument(if (outside) "elsewhere" else "db", "metadata.db", false, false),
                LocalDocument("folder", "Author", true, false)) +
                if (log == null) emptyList() else listOf(LocalDocument("wal", "metadata.db-wal", false, false))
            "folder" -> listOf(LocalDocument("book", "Book.epub", false, false))
            else -> emptyList()
        }
        override fun isWithinRoot(treeUri: String, documentId: String) = documentId != "elsewhere"
        override fun openRead(treeUri: String, documentId: String): InputStream =
            if (documentId == "wal") ByteArrayInputStream(log!!) else onOpen(++reads)
    }

    @Test fun nestedResolutionMissingOutsideAndRevokedGrantAreDistinct() = runTest {
        val documents = Documents()
        val backend = LocalSourceBackend(documents, temporary.newFolder(), SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        val nested = backend.resolve("tree", RelativeSourcePath("Author/Book.epub")) as LocalSourceResult.Available
        assertEquals("book", nested.value.locator.documentId)
        assertFalse(nested.value.writable)
        assertEquals(StorageErrorKind.SOURCE_MISSING, failure(backend.resolve("tree", RelativeSourcePath("missing"))))
        documents.outside = true
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.resolve("tree", RelativeSourcePath("metadata.db"))))
        documents.denied = true
        assertEquals(StorageErrorKind.AUTHORIZATION_EXPIRED, failure(backend.resolve("tree", RelativeSourcePath("metadata.db"))))
    }

    @Test fun dangerousPathsCannotReachDocumentAdapter() {
        listOf("../metadata.db", "/metadata.db", "Author/../metadata.db", "Author\\metadata.db", "Author//Book.epub").forEach {
            try {
                RelativeSourcePath(it)
                throw AssertionError("unsafe path accepted")
            } catch (_: IllegalArgumentException) {
                // Rejected by the logical-path boundary before any provider call.
            }
        }
    }

    @Test fun versionsUseContentEvenWithoutProviderTimes() = runTest {
        val documents = Documents()
        val backend = LocalSourceBackend(documents, temporary.newFolder(), SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        val first = (backend.version("tree", RelativeSourcePath("metadata.db")) as LocalSourceResult.Available).value
        documents.onOpen = { ByteArrayInputStream("different".toByteArray()) }
        val second = (backend.version("tree", RelativeSourcePath("metadata.db")) as LocalSourceResult.Available).value
        assertNotEquals(first, second)
    }

    @Test fun successfulGenerationSurvivesLaterCorruptAndInterruptedAttempts() = runTest {
        val documents = Documents()
        val directory = temporary.newFolder()
        var valid = true
        val backend = LocalSourceBackend(documents, directory, SnapshotValidator { valid }, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val old = (backend.acquireSnapshot("tree", candidate) as LocalSourceResult.Available).value
        assertEquals("database", old.file.readText())
        valid = false
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, failure(backend.acquireSnapshot("tree", candidate)))
        documents.onOpen = { object : InputStream() { override fun read(): Int = throw IOException() } }
        assertEquals(StorageErrorKind.LOCAL_IO, failure(backend.acquireSnapshot("tree", candidate)))
        assertEquals("database", old.file.readText())
        assertEquals(1, File(directory, candidate.toString()).listFiles()!!.size)
    }

    @Test fun secondCompleteReadRejectsChangedContentAndCleansStaging() = runTest {
        val documents = Documents().apply {
            onOpen = { read -> ByteArrayInputStream(if (read == 1) byteArrayOf(1) else byteArrayOf(2)) }
        }
        val directory = temporary.newFolder()
        val backend = LocalSourceBackend(documents, directory, SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failure(backend.acquireSnapshot("tree", UUID.randomUUID())))
        assertTrue(directory.walkTopDown().none { it.isFile })
    }

    @Test fun pendingLogsBeforeOrDuringCopyRejectSnapshot() = runTest {
        val documents = Documents().apply { log = byteArrayOf(1) }
        val backend = LocalSourceBackend(documents, temporary.newFolder(), SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failure(backend.acquireSnapshot("tree", UUID.randomUUID())))
        assertEquals(0, documents.reads)
        documents.log = null
        documents.onOpen = {
            documents.log = byteArrayOf(2)
            ByteArrayInputStream(byteArrayOf(1))
        }
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failure(backend.acquireSnapshot("tree", UUID.randomUUID())))
    }

    @Test fun emptyWalIsReadAndAccepted() = runTest {
        val documents = Documents().apply { log = byteArrayOf() }
        val backend = LocalSourceBackend(documents, temporary.newFolder(), SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        assertTrue(backend.acquireSnapshot("tree", UUID.randomUUID()) is LocalSourceResult.Available)
        assertEquals(2, documents.reads)
    }

    @Test fun queueControlExceptionPropagatesAndCleansStaging() = runTest {
        val directory = temporary.newFolder()
        val backend = LocalSourceBackend(Documents(), directory, SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        var controls = 0
        try {
            backend.acquireSnapshot("tree", UUID.randomUUID()) {
                if (++controls == 2) throw IllegalStateException("control")
            }
            throw AssertionError("control did not propagate")
        } catch (_: IllegalStateException) {
            assertTrue(directory.walkTopDown().none { it.isFile })
        }
    }

    @Test fun rangeResolvesWithinRootAndDoesNotReadThePrefix() = runTest {
        val documents = Documents()
        val backend = LocalSourceBackend(documents, temporary.newFolder(), SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        documents.range = { id, offset ->
            assertEquals("book", id)
            assertEquals(4L, offset)
            ByteArrayInputStream("base".toByteArray())
        }
        val result = backend.openRange("tree", RelativeSourcePath("Author/Book.epub"), 4, FileVersion(BackendKind.LOCAL, "fixture")) as LocalSourceResult.Available
        assertEquals("base", result.value!!.use { it.readBytes().decodeToString() })
        assertEquals(0, documents.reads)
        documents.outside = true
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.openRange("tree", RelativeSourcePath("metadata.db"), 4, FileVersion(BackendKind.LOCAL, "fixture"))))
        documents.denied = true
        assertEquals(StorageErrorKind.AUTHORIZATION_EXPIRED, failure(backend.openRange("tree", RelativeSourcePath("Author/Book.epub"), 4, FileVersion(BackendKind.LOCAL, "fixture"))))
    }

    @Test fun nonSeekableProviderRequestsFullRestartWithoutScanningPrefix() = runTest {
        val documents = Documents()
        val backend = LocalSourceBackend(documents, temporary.newFolder(), SnapshotValidator { true }, StandardTestDispatcher(testScheduler))
        val result = backend.openRange("tree", RelativeSourcePath("Author/Book.epub"), 4, FileVersion(BackendKind.LOCAL, "fixture")) as LocalSourceResult.Available
        org.junit.Assert.assertNull(result.value)
        assertEquals(0, documents.reads)
    }

    private fun failure(result: LocalSourceResult<*>) = (result as LocalSourceResult.Failed).error.kind
}
