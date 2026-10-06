package io.github.chenxiex.calibrecloud.tasks.copies

import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * One format per serial workflow. Durable prefix evidence binds staged bytes to a source version.
 * Recovery verifies the prefix before seeking/range-reading; unsupported ranges restart explicitly.
 * New generations are immutable.
 * A fsynced validated staging file is renamed before the manifest and terminal task state commit in
 * one transaction. Unpublished generations are collected on the next transfer; old open handles
 * retain their generation until close. No ordinary read invokes this handler.
 */
class FormatCopyTaskHandler(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val queue: DurableTaskQueue,
    private val source: FormatSource,
    private val filesDir: File,
    private val io: CoroutineDispatcher,
    private val availableBytes: () -> Long = { filesDir.usableSpace },
) : TaskHandler {
    override fun supports(request: TaskRequest) = request is TaskRequest.FormatCopy || request is TaskRequest.FormatCheck
    override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
    override suspend fun stopped(entry: QueueEntry) = withContext(io) {
        if (entry.control != TaskControl.PAUSE) discardStaging(entry.record.id)
        Unit
    }
    override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision = withContext(io) {
        execution.checkControl()
        if (entry.record.submission.request is TaskRequest.FormatCheck)
            return@withContext RecoveryDecision(TaskStage.FORMAT_CHECK, null)
        val evidence = entry.checkpoint?.let { readEvidence(entry.record.id, it) }
        RecoveryDecision(if (evidence?.complete == true) TaskStage.FORMAT_PUBLISH else TaskStage.FORMAT_TRANSFER,
            if (evidence != null) entry.checkpoint else null)
    }

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome = withContext(io) {
        val request = entry.record.submission.request
        val key = when (request) {
            is TaskRequest.FormatCopy -> CopyKey(request.resource.book, request.resource.format)
            is TaskRequest.FormatCheck -> request.key
            else -> error("Unsupported copy request")
        }
        val previous = state.find(key)
        if (request is TaskRequest.FormatCheck && previous == null) return@withContext StageOutcome.Complete()
        val imported = metadata.currentImport()
        if (imported?.identity?.id != key.book.libraryId) return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
        val book = imported.metadata.books.find { it.sourceId == key.book.sourceId && it.sourceUuid == key.book.sourceUuid }
        val format = book?.formats?.find { it.format == key.format }
        if (format == null) {
            state.confirmSource(key, SourceAvailability.CONFIRMED_MISSING)
            return@withContext if (request is TaskRequest.FormatCheck) StageOutcome.Complete() else fail(StorageErrorKind.SOURCE_MISSING)
        }
        val location = imported.identity.location
        val path = format.path
        if (request is TaskRequest.FormatCopy && request.resource.source != SourceFileLocator.Relative(location.backend, path))
            return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
        suspend fun check() {
            execution.checkControl()
            if (state.current()?.identity != imported.identity) throw StaleCopyBinding()
        }
        var retainTransfer = entry.checkpoint?.let { readEvidence(entry.record.id, it) } != null
        try {
            check()
            val version = source.versionChecked(location, path, ::check)
            check()
            if (request is TaskRequest.FormatCheck) {
                state.confirmSource(key, SourceAvailability.AVAILABLE)
                if (previous!!.savedVersion != version) queue.submit(TaskSubmission(
                    TaskRequest.FormatCopy(FormatResource(key.book, key.format, SourceFileLocator.Relative(location.backend, path)), version),
                    TaskOrigin.DOWNLOADED_FORMAT_UPDATE))
                return@withContext StageOutcome.Complete()
            }
            request as TaskRequest.FormatCopy
            if (request.expectedVersion != null && request.expectedVersion != version)
                throw FormatSourceFailure(StorageError(StorageErrorKind.VERSION_CONFLICT))
            if (entry.stage == TaskStage.FORMAT_TRANSFER) {
                state.collectUnreferenced(key.book.libraryId)
                val size = source.size(location, path)
                var checkpoint = entry.checkpoint?.takeIf { it.version == version }
                var evidence = checkpoint?.let { readEvidence(entry.record.id, it) }
                if (evidence != null && (evidence.total != size || (size != null && evidence.offset > size))) evidence = null
                if (evidence == null) {
                    discardStaging(entry.record.id)
                    checkpoint = RecoveryCheckpoint(UUID.randomUUID(), version)
                }
                val generation = checkpoint!!.generation
                val directory = staging(entry.record.id)
                check(directory.mkdirs() || directory.isDirectory)
                val target = privateFile("book-staging/${entry.record.id.value}/$generation.part")
                var transferred = evidence?.offset ?: 0L
                var input = if (transferred > 0 && (size == null || transferred < size))
                    source.openRange(location, path, transferred, version) else null
                if (transferred > 0 && (size == null || transferred < size) && input == null) {
                    transferred = 0
                    evidence = null
                }
                var durableOffset = transferred
                try {
                    requireSpace(if (size == null) 0 else size - transferred)
                    execution.checkpoint(checkpoint, TaskProgress(transferred, size))
                    val digest = MessageDigest.getInstance("SHA-256")
                    if (transferred > 0) {
                        hashPrefix(target, transferred, digest, ::check)
                        RandomAccessFile(target, "rw").use { it.setLength(transferred) }
                    }
                    var lastProgress = System.nanoTime()
                    if (size == null || transferred < size || transferred == 0L) {
                        if (input == null) input = source.open(location, path)
                        val transferInput = input!!
                        input = null
                        transferInput.use { stream -> FileOutputStream(target, transferred > 0).use { output ->
                            try {
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    check()
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    requireSpace(count.toLong())
                                    if (size != null && count > size - transferred)
                                        throw FormatSourceFailure(StorageError(StorageErrorKind.CORRUPT_CONTENT))
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    transferred += count
                                    if (System.nanoTime() - lastProgress >= 2_000_000_000L) {
                                        output.fd.sync()
                                        durableOffset = transferred
                                        saveEvidence(entry.record.id, checkpoint, target, transferred, size, false)
                                        execution.checkpoint(checkpoint, TaskProgress(transferred, size))
                                        lastProgress = System.nanoTime()
                                    }
                                }
                            } finally { output.fd.sync(); durableOffset = transferred }
                        } }
                    }
                    if (transferred > 0) {
                        saveEvidence(entry.record.id, checkpoint, target, transferred, size, false)
                        retainTransfer = true
                    }
                    check()
                    if (transferred <= 0 || (size != null && transferred != size))
                        throw FormatSourceFailure(StorageError(StorageErrorKind.CORRUPT_CONTENT))
                    if (location.backend == BackendKind.LOCAL && version.token != digest.digest().joinToString("") { "%02x".format(it) }) {
                        throw FormatSourceFailure(StorageError(StorageErrorKind.VERSION_CONFLICT))
                    }
                    validate(target, key.format, ::check)
                    if (source.versionChecked(location, path, ::check) != version) {
                        throw FormatSourceFailure(StorageError(StorageErrorKind.VERSION_CONFLICT))
                    }
                    check()
                    saveEvidence(entry.record.id, checkpoint, target, transferred, size, true)
                    execution.checkpoint(checkpoint, TaskProgress(transferred, size))
                    return@withContext StageOutcome.Advance(TaskStage.FORMAT_PUBLISH)
                } finally {
                    try { input?.close() } finally {
                        // Control exceptions must not prevent durable prefix recording. The coordinator
                        // subsequently distinguishes pause (retain) from cancel (delete).
                        withContext(NonCancellable) {
                            if (durableOffset > 0 && target.isFile && target.length() >= durableOffset) {
                                saveEvidence(entry.record.id, checkpoint, target, durableOffset, size,
                                    readEvidence(entry.record.id, checkpoint)?.complete == true)
                                retainTransfer = true
                            }
                        }
                    }
                }
            }
            val checkpoint = entry.checkpoint ?: return@withContext fail(StorageErrorKind.CORRUPT_CONTENT)
            if (checkpoint.version != version) throw FormatSourceFailure(StorageError(StorageErrorKind.VERSION_CONFLICT))
            val target = privateFile("book-staging/${entry.record.id.value}/${checkpoint.generation}.part")
            validate(target, key.format, ::check)
            check()
            val complete = privateFile("books/${key.book.libraryId.value}/${checkpoint.generation}.book")
            check(complete.parentFile!!.mkdirs() || complete.parentFile!!.isDirectory)
            if (complete.exists()) return@withContext fail(StorageErrorKind.CORRUPT_CONTENT)
            if (!target.renameTo(complete)) return@withContext fail(StorageErrorKind.LOCAL_IO)
            var published = false
            try {
                syncDirectory(complete.parentFile!!)
                syncDirectory(complete.parentFile!!.parentFile!!)
                syncDirectory(filesDir)
                syncDirectory(staging(entry.record.id))
                check()
                published = state.publishComplete(DownloadedCopy(key, CompleteCopyLocation(key.book.libraryId, checkpoint.generation),
                    book.title, complete.length(), version, SourceAvailability.AVAILABLE), entry.record.id.value)
                if (!published) { execution.checkControl(); return@withContext fail(StorageErrorKind.VERSION_CONFLICT) }
                StageOutcome.Complete(cachePublished = true)
            } finally {
                if (!published) complete.delete()
                discardStaging(entry.record.id)
            }
        } catch (failure: FormatSourceFailure) {
            if (failure.error.kind in setOf(StorageErrorKind.VERSION_CONFLICT, StorageErrorKind.CORRUPT_CONTENT,
                    StorageErrorKind.SOURCE_MISSING)) retainTransfer = false
            if (failure.error.kind == StorageErrorKind.SOURCE_MISSING) state.confirmSource(key, SourceAvailability.CONFIRMED_MISSING)
            when {
                failure.transient || failure.error.kind == StorageErrorKind.NO_NETWORK -> StageOutcome.Retry(TaskError.Source(failure.error), failure.retryDelayMillis)
                else -> StageOutcome.Fail(TaskError.Source(failure.error))
            }
        } catch (_: StaleCopyBinding) {
            retainTransfer = false
            fail(StorageErrorKind.VERSION_CONFLICT)
        } catch (_: IOException) {
            fail(if (availableBytes() < RESERVE_BYTES) StorageErrorKind.INSUFFICIENT_SPACE else StorageErrorKind.LOCAL_IO)
        } finally {
            // Partial files are private recovery evidence only; normal readers use published generations.
            if (!retainTransfer) discardStaging(entry.record.id)
        }
    }

    private data class PrefixEvidence(val offset: Long, val total: Long?, val complete: Boolean)

    private fun evidenceFile(id: TaskId, checkpoint: RecoveryCheckpoint) =
        privateFile("book-staging/${id.value}/${checkpoint.generation}.resume")

    /** Only bytes below the fsynced offset are reusable; an unconfirmed crash tail is ignored. */
    private suspend fun readEvidence(id: TaskId, checkpoint: RecoveryCheckpoint): PrefixEvidence? = try {
        val json = JSONObject(evidenceFile(id, checkpoint).readText())
        val offset = json.getLong("offset")
        val total = if (json.isNull("total")) null else json.getLong("total")
        val target = privateFile("book-staging/${id.value}/${checkpoint.generation}.part")
        if (offset <= 0 || (total != null && (total <= 0 || offset > total)) || !target.isFile || target.length() < offset ||
            json.getString("version") != checkpoint.version?.token || json.getString("backend") != checkpoint.version?.backend?.name)
            null
        else {
            val digest = MessageDigest.getInstance("SHA-256")
            hashPrefix(target, offset, digest) {}
            if (digest.digest().joinToString("") { "%02x".format(it) } != json.getString("sha256")) null
            else PrefixEvidence(offset, total, json.getBoolean("complete") && target.length() == offset && (total == null || offset == total))
        }
    } catch (_: Exception) { null }

    private suspend fun saveEvidence(id: TaskId, checkpoint: RecoveryCheckpoint, target: File, offset: Long, total: Long?, complete: Boolean) {
        val digest = MessageDigest.getInstance("SHA-256")
        hashPrefix(target, offset, digest) {}
        val json = JSONObject().put("offset", offset).put("total", total ?: JSONObject.NULL)
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            .put("version", checkpoint.version!!.token).put("backend", checkpoint.version.backend.name).put("complete", complete)
        val destination = evidenceFile(id, checkpoint)
        val temporary = privateFile("book-staging/${id.value}/${checkpoint.generation}.resume.tmp")
        FileOutputStream(temporary).use { output -> output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        if (!temporary.renameTo(destination)) throw IOException()
        syncDirectory(destination.parentFile!!)
        syncDirectory(destination.parentFile!!.parentFile!!)
        syncDirectory(filesDir)
    }

    private suspend fun hashPrefix(file: File, length: Long, digest: MessageDigest, control: suspend () -> Unit) {
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                control()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) throw IOException()
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }

    private fun requireSpace(bytes: Long) {
        if (bytes < 0 || availableBytes() < RESERVE_BYTES || availableBytes() - RESERVE_BYTES < bytes)
            throw FormatSourceFailure(StorageError(StorageErrorKind.INSUFFICIENT_SPACE))
    }
    private class StaleCopyBinding : RuntimeException()

    private fun privateFile(relative: String): File {
        val root = filesDir.toPath().toRealPath()
        var component = root
        relative.split('/').forEach { name ->
            component = component.resolve(name)
            if (Files.isSymbolicLink(component)) throw FormatSourceFailure(StorageError(StorageErrorKind.CORRUPT_CONTENT))
        }
        return component.toFile()
    }
    private fun staging(id: TaskId) = privateFile("book-staging/${id.value}")

    /** No following links, including links created inside an abandoned staging directory. */
    private fun discardStaging(id: TaskId) {
        val directory = try { staging(id).toPath() } catch (_: IOException) { return }
        if (!Files.exists(directory)) return
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private suspend fun validate(file: File, format: BookFormat, control: suspend () -> Unit) {
        if (!file.isFile || file.length() <= 0) throw FormatSourceFailure(StorageError(StorageErrorKind.CORRUPT_CONTENT))
        try {
            when (format.value) {
                "EPUB" -> ZipFile(file).use { zip ->
                    val mime = zip.getEntry("mimetype") ?: throw IOException()
                    if (zip.getInputStream(mime).use { input ->
                        "application/epub+zip".toByteArray(Charsets.US_ASCII).all { input.read() == it.toInt() } && input.read() == -1
                    }.not() ||
                        zip.getEntry("META-INF/container.xml") == null) throw IOException()
                    val entries = zip.entries()
                    val buffer = ByteArray(64 * 1024)
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val crc = CRC32()
                        var length = 0L
                        zip.getInputStream(entry).use { input ->
                            while (true) {
                                control()
                                val count = input.read(buffer)
                                if (count < 0) break
                                crc.update(buffer, 0, count); length += count
                            }
                        }
                        if (entry.size != length || entry.crc != crc.value) throw IOException()
                    }
                }
                "PDF" -> RandomAccessFile(file, "r").use { input ->
                    val header = ByteArray(5)
                    input.readFully(header)
                    if (header.toString(Charsets.US_ASCII) != "%PDF-") throw IOException()
                    input.seek(maxOf(0, input.length() - 2048))
                    val tail = ByteArray(minOf(2048L, input.length()).toInt())
                    input.readFully(tail)
                    if (!tail.toString(Charsets.ISO_8859_1).contains("%%EOF")) throw IOException()
                }
            }
        } catch (failure: FormatSourceFailure) { throw failure }
        catch (_: IOException) { throw FormatSourceFailure(StorageError(StorageErrorKind.CORRUPT_CONTENT)) }
    }
    private fun fail(kind: StorageErrorKind) = StageOutcome.Fail(TaskError.Source(StorageError(kind)))
    companion object {
        private const val RESERVE_BYTES = 1024L * 1024
    }
}
