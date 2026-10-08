package io.github.chenxiex.calibrecloud.tasks.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import androidx.core.graphics.scale
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Bounded image transfer, sampled decode and immutable PNG publication. Recovery restarts this small
 * resource rather than trusting a partially decoded image. Pause/cancel stop at checked boundaries.
 * The SQLite pointer and terminal task result commit together; failures retain the previous image.
 * The source is observed once per transfer and asked whether it is unchanged before publication.
 * When the source resyncs missing paths, a missing cover path first triggers one metadata sync and one retry (R11).
 */
class CoverTaskHandler(private val state: ApplicationStateRepository, private val metadata: MetadataRepository,
    private val covers: CoverRepository, private val sources: LibrarySources,
    private val io: CoroutineDispatcher, private val queue: DurableTaskQueue? = null,
    private val requestSync: suspend (TaskOrigin) -> TaskId? = { null }) : TaskHandler {
    override fun supports(request: TaskRequest) = request is TaskRequest.CoverLoad
    override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
    override suspend fun stopped(entry: QueueEntry) = withContext(io) { discard(entry.record.id) }
    override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision = withContext(io) {
        execution.checkControl()
        discard(entry.record.id)
        RecoveryDecision(TaskStage.COVER_TRANSFER, null)
    }

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome = withContext(io) {
        val book = (entry.record.submission.request as TaskRequest.CoverLoad).book
        val imported = metadata.currentImport() ?: return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
        val item = imported.metadata.books.find { it.sourceId == book.sourceId && it.sourceUuid == book.sourceUuid }
        if (imported.identity.id != book.libraryId || item == null) return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
        if (!item.hasCover) return@withContext fail(StorageErrorKind.SOURCE_MISSING)
        val path = RelativeSourcePath("${item.path.value}/cover.jpg")
        val location = imported.identity.location
        val source = sources.of(location)
        suspend fun checkBinding() {
            execution.checkControl()
            if (!covers.isCurrent(book, imported.generation))
                throw SourceFailure(StorageError(StorageErrorKind.VERSION_CONFLICT))
        }
        var transferReady = false
        try {
            checkBinding()
            if (entry.knownMissing(path)) throw SourceFailure(StorageError(StorageErrorKind.SOURCE_MISSING))
            val directory = staging(entry.record.id)
            if (entry.stage == TaskStage.COVER_TRANSFER) {
                discard(entry.record.id)
                require(directory.mkdirs() || directory.isDirectory)
                val generation = UUID.randomUUID()
                val raw = File(directory, "source.part")
                val opened = source.openCover(location, path, CoverRepository.WIDTH, CoverRepository.HEIGHT, ::checkBinding)
                val version = opened.version
                opened.input.use { input -> FileOutputStream(raw).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var transferred = 0L
                    while (true) {
                        checkBinding()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        transferred += count
                        if (transferred > CoverRepository.MAX_ENCODED_BYTES) return@withContext fail(StorageErrorKind.CORRUPT_CONTENT)
                        output.write(buffer, 0, count)
                    }
                } }
                checkBinding()
                val bitmap = decode(raw) ?: return@withContext fail(StorageErrorKind.CORRUPT_CONTENT)
                try {
                    FileOutputStream(File(directory, "$generation.png")).use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return@withContext fail(StorageErrorKind.CORRUPT_CONTENT)
                        output.fd.sync()
                    }
                } finally { bitmap.recycle() }
                checkBinding()
                if (!source.unchanged(location, path, version, ::checkBinding))
                    return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
                FileOutputStream(File(directory, "import.json")).use {
                    it.write(JSONObject().put("import", imported.generation.toString()).toString().toByteArray())
                    it.fd.sync()
                }
                execution.checkpoint(RecoveryCheckpoint(generation, version))
                transferReady = true
                raw.delete()
                return@withContext StageOutcome.Advance(TaskStage.COVER_PUBLISH)
            }
            val checkpoint = entry.checkpoint ?: return@withContext fail(StorageErrorKind.CORRUPT_CONTENT)
            val version = checkpoint.version ?: return@withContext fail(StorageErrorKind.CORRUPT_CONTENT)
            if (!source.unchanged(location, path, version, ::checkBinding))
                return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
            val generation = checkpoint.generation
            val savedImport = UUID.fromString(JSONObject(File(directory, "import.json").readText()).getString("import"))
            if (savedImport != imported.generation) return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
            checkBinding()
            val target = covers.file(book, generation)
            require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            val staged = File(directory, "$generation.png")
            if (target.exists() || !staged.renameTo(target)) return@withContext fail(StorageErrorKind.LOCAL_IO)
            var published = false
            try {
                sync(target.parentFile!!)
                sync(target.parentFile!!.parentFile!!)
                sync(target.parentFile!!.parentFile!!.parentFile!!)
                sync(directory)
                published = covers.publish(book, generation, savedImport, entry.record.id.value)
                if (!published) return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
            } finally { if (!published) target.delete() }
            safeDiscard(entry.record.id)
            StageOutcome.Complete(cachePublished = true)
        } catch (failure: SourceFailure) {
            if (failure.error.kind == StorageErrorKind.SOURCE_MISSING && queue != null) {
                val stale = staleSource(entry, source, path, queue, requestSync)
                if (stale is StaleSource.Await) return@withContext stale.outcome
            }
            val error = TaskError.Source(failure.error)
            val wait = authorizationWait(source, failure.error.kind)
            when {
                failure.transient -> StageOutcome.Retry(error, failure.retryDelayMillis)
                failure.error.kind == StorageErrorKind.NO_NETWORK -> StageOutcome.Wait(WaitingReason.NETWORK)
                wait != null -> StageOutcome.Wait(wait)
                else -> StageOutcome.Fail(error)
            }
        } catch (_: IOException) { fail(StorageErrorKind.LOCAL_IO) }
        finally {
            // Only the successful transfer retains a scaled image for the next stage. Other exits,
            // including control exceptions, discard private staging and keep complete cache intact.
            if (!transferReady) safeDiscard(entry.record.id)
        }
    }

    private fun decode(raw: File): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(raw.path, options)
        if (options.outWidth !in 1..32768 || options.outHeight !in 1..32768 ||
            options.outWidth.toLong() * options.outHeight > 100_000_000L) return null
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > CoverRepository.WIDTH * 2 ||
            options.outHeight / options.inSampleSize > CoverRepository.HEIGHT * 2) options.inSampleSize *= 2
        val decoded = BitmapFactory.decodeFile(raw.path, options) ?: return null
        val ratio = minOf(1.0, CoverRepository.WIDTH.toDouble() / decoded.width, CoverRepository.HEIGHT.toDouble() / decoded.height)
        if (ratio == 1.0) return decoded
        return try {
            decoded.scale(maxOf(1, (decoded.width * ratio).toInt()), maxOf(1, (decoded.height * ratio).toInt()))
        } finally { decoded.recycle() }
    }
    private fun staging(id: TaskId) = covers.privatePath("cover-staging/${id.value}")
    private fun discard(id: TaskId) {
        val directory = staging(id)
        directory.listFiles().orEmpty().forEach { file ->
            if (!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isFile) file.delete()
        }
        directory.delete()
    }
    private fun safeDiscard(id: TaskId) {
        try { discard(id) } catch (_: Exception) {
            android.util.Log.w("CoverCache", "stage=staging_cleanup_failed")
        }
    }
    private fun sync(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    private fun fail(kind: StorageErrorKind) = StageOutcome.Fail(TaskError.Source(StorageError(kind)))
}
