package io.github.chenxiex.calibrecloud.storage.cache

import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.storage.api.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors

class PrivateCopyReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val library = LibraryId(UUID.randomUUID())
    private val key = CopyKey(BookKey(library, 1, UUID.randomUUID()), BookFormat.parse("epub"))
    private val version = FileVersion(BackendKind.ONEDRIVE, "opaque-etag")

    private fun record(key: CopyKey = this.key, size: Long? = 8) = DownloadedCopy(
        key, CompleteCopyLocation(key.book.libraryId, UUID.randomUUID()), "Test title", size,
        version, SourceAvailability.UNCONFIRMED,
    )

    private fun file(root: File, record: DownloadedCopy): File = File(
        root, "books/${record.location.libraryId.value}/${record.location.fileGeneration}.book",
    )

    @Test
    fun completeCopyIsReadableCloseableAndIndependentOfSourceOnIoDispatcher() = runTest {
        val root = temporary.newFolder("private")
        val source = temporary.newFolder("source")
        val sourceBook = File(source, "source.epub").apply { writeText("original") }
        val copy = record()
        file(root, copy).apply { parentFile!!.mkdirs(); writeText("complete") }
        var sourceVisits = 0
        val sourceAccess = { sourceVisits++; sourceBook.readText() }
        val factory = PrivateBookFiles(root)
        Executors.newSingleThreadExecutor { task -> Thread(task, "copy-io") }.asCoroutineDispatcher().use { io ->
            val reader = PrivateCopyReader(CompleteCopyQuery {
                assertEquals("copy-io", Thread.currentThread().name)
                copy.takeIf { record -> record.key == it }
            }, ApplicationCopyHandleFactory { location, size ->
                assertEquals("copy-io", Thread.currentThread().name)
                factory.open(location, size)
            }, io)
            val result = reader.read(key) as CopyReadResult.Available
            assertEquals(version, result.version)
            assertEquals(8, result.handle.sizeBytes)
            result.handle.use { assertEquals("complete", it.input.reader().readText()) }
            assertThrows(IOException::class.java) { result.handle.input.read() }
        }
        assertEquals(0, sourceVisits)
        // The source fixture is only inspected after the ordinary read has finished.
        assertEquals("original", sourceAccess())
        assertEquals(1, sourceVisits)
    }

    @Test
    fun missingRecordNeverOpensAFileAndStagingIsInvisible() = runTest {
        val root = temporary.newFolder()
        File(root, "staging/partial.part").apply { parentFile!!.mkdirs(); writeText("partial") }
        var opens = 0
        val reader = PrivateCopyReader(CompleteCopyQuery { null }, ApplicationCopyHandleFactory { _, _ ->
            opens++
            error("No complete record")
        }, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        assertEquals(CopyReadResult.Missing, reader.read(key))
        assertEquals(0, opens)
    }

    @Test
    fun deletedCompleteFileIsMissing() = runTest {
        val copy = record()
        val reader = PrivateCopyReader(CompleteCopyQuery { copy }, PrivateBookFiles(temporary.newFolder()),
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        assertEquals(CopyReadResult.Missing, reader.read(key))
    }

    @Test
    fun libraryAndReusedBookIdAndFormatCannotShareCopies() = runTest {
        val root = temporary.newFolder()
        val copy = record()
        file(root, copy).apply { parentFile!!.mkdirs(); writeText("complete") }
        val reader = PrivateCopyReader(CompleteCopyQuery { copy.takeIf { record -> record.key == it } },
            PrivateBookFiles(root), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val others = listOf(
            key.copy(book = key.book.copy(libraryId = LibraryId(UUID.randomUUID()))),
            key.copy(book = key.book.copy(sourceUuid = UUID.randomUUID())),
            key.copy(format = BookFormat.parse("pdf")),
        )
        others.forEach { assertEquals(CopyReadResult.Missing, reader.read(it)) }
        (reader.read(key) as CopyReadResult.Available).handle.close()
    }

    @Test
    fun malformedQueryRecordIsRejectedBeforeFileOpen() = runTest {
        val other = record(key.copy(book = key.book.copy(sourceUuid = UUID.randomUUID())))
        val reader = PrivateCopyReader(CompleteCopyQuery { other }, ApplicationCopyHandleFactory { _, _ ->
            error("Invalid identity must not reach files")
        }, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        assertEquals(CopyReadResult.Failed(StorageError(StorageErrorKind.CORRUPT_CONTENT)), reader.read(key))
    }

    @Test
    fun emptyTruncatedAndNonRegularFilesFailRatherThanReturnEmptyContent() {
        val root = temporary.newFolder()
        val copy = record()
        val target = file(root, copy).apply { parentFile!!.mkdirs(); writeText("") }
        val factory = PrivateBookFiles(root)
        assertEquals(HandleOpenResult.Failed(StorageError(StorageErrorKind.CORRUPT_CONTENT)),
            factory.open(copy.location, copy.sizeBytes))
        target.writeText("short")
        assertEquals(HandleOpenResult.Failed(StorageError(StorageErrorKind.CORRUPT_CONTENT)),
            factory.open(copy.location, copy.sizeBytes))
        target.delete()
        target.mkdir()
        assertEquals(HandleOpenResult.Failed(StorageError(StorageErrorKind.CORRUPT_CONTENT)),
            factory.open(copy.location, null))
    }

    @Test
    fun symlinkCannotEscapePrivateCopyRootOrAliasAnotherLibrary() {
        val root = temporary.newFolder()
        val copy = record()
        val outside = temporary.newFile().apply { writeText("complete") }
        val target = file(root, copy).apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        val factory = PrivateBookFiles(root)
        assertEquals(HandleOpenResult.Failed(StorageError(StorageErrorKind.CORRUPT_CONTENT)),
            factory.open(copy.location, 8))
        target.delete()
        target.parentFile!!.delete()
        Files.createSymbolicLink(target.parentFile!!.toPath(), outside.parentFile!!.toPath())
        assertEquals(HandleOpenResult.Failed(StorageError(StorageErrorKind.CORRUPT_CONTENT)),
            factory.open(copy.location, null))
    }

    @Test
    fun permissionAndIoFailuresAreStructuredAndDoNotExposeExceptionText() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val permission = PrivateCopyReader(CompleteCopyQuery { throw SecurityException("secret URL") },
            PrivateBookFiles(temporary.newFolder()), dispatcher)
        assertEquals(CopyReadResult.Failed(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED)), permission.read(key))
        val io = PrivateCopyReader(CompleteCopyQuery { throw IOException("private path") },
            PrivateBookFiles(temporary.newFolder()), dispatcher)
        assertEquals(CopyReadResult.Failed(StorageError(StorageErrorKind.LOCAL_IO)), io.read(key))
    }

    @Test
    fun confirmedMissingSourceDoesNotPreventOpeningCompleteOldCopy() = runTest {
        val root = temporary.newFolder()
        val copy = record().copy(sourceAvailability = SourceAvailability.CONFIRMED_MISSING)
        file(root, copy).apply { parentFile!!.mkdirs(); writeText("complete") }
        val reader = PrivateCopyReader(CompleteCopyQuery { copy }, PrivateBookFiles(root),
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        (reader.read(key) as CopyReadResult.Available).handle.use {
            assertEquals("complete", it.input.reader().readText())
        }
    }
    @Test
    fun cancellationBeforeHandleDeliveryClosesTheUndeliveredCopy() = runTest {
        var closed = false
        val handle = object : ApplicationCopyHandle {
            override val input = java.io.ByteArrayInputStream(byteArrayOf(1))
            override val sizeBytes = 1L
            override fun close() { closed = true; input.close() }
        }
        lateinit var reading: Job
        val reader = PrivateCopyReader(CompleteCopyQuery { record(size = 1) },
            ApplicationCopyHandleFactory { _, _ ->
                reading.cancel()
                HandleOpenResult.Opened(handle)
            }, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        reading = launch { reader.read(key) }
        reading.join()
        assertTrue(closed)
    }

}
