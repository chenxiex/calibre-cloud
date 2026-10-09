package io.github.chenxiex.calibrecloud.tasks.covers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Loads the covers of one shown page in order (R10): bounded image transfer, sampled decode and
 * immutable PNG publication, up to [LibrarySource.parallelReads] covers at once. Each cover is published on its own, so a
 * paused, yielded or interrupted batch resumes after the covers it already published and restarts
 * the one it was loading rather than trusting a partially decoded image. A cover that is missing or
 * corrupt keeps its placeholder without stopping the others; a lost import, grant or network ends
 * or suspends the batch. The source is read once per cover and the image published is the version
 * that read returned; it is not read again before publication, as the version already names exactly
 * the bytes decoded. When the source resyncs missing paths, the first missing cover triggers one
 * metadata sync for the batch (R11). A newer page's batch replaces this one: no new cover starts and
 * those started finish first.
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

    /** The import the batch was loading for is no longer current: the whole batch is stale. */
    private class ImportChanged : RuntimeException()

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome = withContext(io) {
        val request = entry.record.submission.request as TaskRequest.CoverLoad
        val imported = metadata.currentImport() ?: return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
        if (imported.identity.id != request.libraryId) return@withContext fail(StorageErrorKind.VERSION_CONFLICT)
        val books = imported.metadata.books.associateBy { it.sourceId to it.sourceUuid }
        val location = imported.identity.location
        val source = sources.of(location)
        val first = request.books.first()
        suspend fun checkBinding() {
            execution.checkControl()
            if (!covers.isCurrent(first, imported.generation)) throw ImportChanged()
        }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<BookFailure>()
        val processed = AtomicLong()
        val published = AtomicBoolean()
        val progress = Mutex()
        val stale = Mutex()
        var awaitSync: StageOutcome.AwaitSync? = null
        var replaced = false
        suspend fun one(book: BookKey) {
            val item = books[book.sourceId to book.sourceUuid]
            val path = item?.let { RelativeSourcePath("${it.path.value}/cover.jpg") }
            val error = when {
                item == null -> StorageErrorKind.VERSION_CONFLICT
                !item.hasCover || entry.knownMissing(path!!) -> StorageErrorKind.SOURCE_MISSING
                covers.cached(book) -> null
                else -> try {
                    if (load(entry.record.id, book, imported.generation, location, source, path, ::checkBinding)) {
                        published.set(true)
                        null
                    } else StorageErrorKind.VERSION_CONFLICT
                } catch (failure: SourceFailure) {
                    when (failure.error.kind) {
                        StorageErrorKind.SOURCE_MISSING -> stale.withLock {
                            // One metadata sync serves the batch; covers waiting for it run again afterwards.
                            if (awaitSync != null) return
                            val found = if (queue != null) staleSource(entry, source, path, queue, requestSync) else StaleSource.Confirmed
                            if (found is StaleSource.Await) { awaitSync = found.outcome; return }
                            StorageErrorKind.SOURCE_MISSING
                        }
                        StorageErrorKind.CORRUPT_CONTENT, StorageErrorKind.VERSION_CONFLICT -> failure.error.kind
                        else -> throw failure
                    }
                }
            }
            if (error != null) failures.add(BookFailure(book, TaskError.Source(StorageError(error))))
            progress.withLock { execution.progress(TaskProgress(processed.incrementAndGet(), request.books.size.toLong())) }
        }
        try {
            // Up to the backend's limit at once; a failure of the batch cancels the covers in flight.
            coroutineScope {
                val permits = Semaphore(source.parallelReads)
                var started = 0
                for (book in request.books) {
                    permits.acquire()
                    try {
                        checkBinding()
                        if (stale.withLock { awaitSync } != null) break
                        // A newer page's batch replaces this one: no new cover starts, those started finish.
                        if (started > 0 && queue?.newerCoverBatch(entry.record.id) == true) { replaced = true; break }
                    } catch (failure: Throwable) {
                        permits.release()
                        throw failure
                    }
                    started++
                    launch { try { one(book) } finally { permits.release() } }
                }
            }
            if (replaced) execution.cancelHere()
            awaitSync ?: StageOutcome.Complete(if (failures.isEmpty()) TaskResult.Completed
                else TaskResult.CompletedWithBookFailures(FrozenSet(failures.toList())), cachePublished = published.get())
        } catch (_: ImportChanged) {
            fail(StorageErrorKind.VERSION_CONFLICT)
        } catch (failure: SourceFailure) {
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
            // Only complete images leave staging; every exit, including control exceptions, discards it.
            safeDiscard(entry.record.id)
        }
    }

    /**
     * Reads, scales and publishes the cover of [book] at [path]. Returns false when the publication gate
     * refused it; throws SourceFailure for a source or content failure of this cover.
     */
    private suspend fun load(taskId: TaskId, book: BookKey, importGeneration: UUID, location: LibraryLocation,
        source: LibrarySource, path: RelativeSourcePath, checkBinding: suspend () -> Unit): Boolean {
        // Each cover in flight has its own staging directory below the batch's.
        val directory = covers.privatePath("cover-staging/${taskId.value}/${book.sourceId}-${book.sourceUuid}")
        deleteFiles(directory)
        require(directory.mkdirs() || directory.isDirectory)
        try {
            return publishFrom(directory, taskId, book, importGeneration, location, source, path, checkBinding)
        } finally { deleteFiles(directory) }
    }

    private suspend fun publishFrom(directory: File, taskId: TaskId, book: BookKey, importGeneration: UUID, location: LibraryLocation,
        source: LibrarySource, path: RelativeSourcePath, checkBinding: suspend () -> Unit): Boolean {
        val generation = UUID.randomUUID()
        val raw = File(directory, "source.part")
        val opened = source.openCover(location, path, CoverRepository.WIDTH, CoverRepository.HEIGHT, checkBinding)
        opened.input.use { input -> FileOutputStream(raw).use { output ->
            val buffer = ByteArray(32 * 1024)
            var transferred = 0L
            while (true) {
                checkBinding()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                transferred += count
                if (transferred > CoverRepository.MAX_ENCODED_BYTES) throw SourceFailure(StorageErrorKind.CORRUPT_CONTENT)
                output.write(buffer, 0, count)
            }
        } }
        checkBinding()
        val bitmap = decode(raw) ?: throw SourceFailure(StorageErrorKind.CORRUPT_CONTENT)
        val staged = File(directory, "$generation.png")
        try {
            FileOutputStream(staged).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw SourceFailure(StorageErrorKind.CORRUPT_CONTENT)
                output.fd.sync()
            }
        } finally { bitmap.recycle() }
        checkBinding()
        val published = covers.publish(book, staged, importGeneration, taskId.value)
        // A refused publication is a pending control or a changed import, which the next boundary applies.
        if (!published) checkBinding()
        return published
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
    /** Removes the batch's staging, including the directory of each cover in flight. */
    private fun discard(id: TaskId) {
        val directory = staging(id)
        directory.listFiles().orEmpty().forEach { entry ->
            if (!java.nio.file.Files.isSymbolicLink(entry.toPath()) && entry.isDirectory) deleteFiles(entry)
        }
        deleteFiles(directory)
    }

    /** Deletes the regular files of [directory], never following links, then the directory once empty. */
    private fun deleteFiles(directory: File) {
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
    private fun fail(kind: StorageErrorKind) = StageOutcome.Fail(TaskError.Source(StorageError(kind)))
}
