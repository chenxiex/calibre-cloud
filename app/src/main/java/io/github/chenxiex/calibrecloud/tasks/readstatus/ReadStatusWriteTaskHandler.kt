package io.github.chenxiex.calibrecloud.tasks.readstatus

import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.metadata.ReadStatusStaging
import io.github.chenxiex.calibrecloud.metadata.ReadStatusStagingException
import io.github.chenxiex.calibrecloud.metadata.StagedBookOutcome
import io.github.chenxiex.calibrecloud.metadata.StagedDatabase
import io.github.chenxiex.calibrecloud.metadata.StagingFailure
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes a read status task's change list to its library (R14–R16). Each round fetches the latest
 * database, applies the targets and pushes it with the fetched version as precondition:
 *
 * - WRITE_SNAPSHOT first lets the source finish a push the task's journal shows was interrupted, then
 *   acquires a new snapshot and moves it into the task's private `write-staging` directory.
 * - WRITE_PREPARE checks that [TaskRequest.ReadStatusWrite.column] is still the library's read column and
 *   builds the staged database with [ReadStatusStaging]; when every book already holds its target there
 *   is nothing to push.
 * - WRITE_COMMIT pushes it; neither pause nor cancel applies.
 *
 * A rejected push, a network failure or an unknown result is retried as a whole round: recovery always
 * returns to WRITE_SNAPSHOT and discards the round's files, so a round never reuses an older database.
 * The prepared result is therefore kept in memory between the stages of one round only. After a push,
 * or when nothing needed one, the library sync is requested through [requestSync] with the task's
 * origin and becomes the next task (R16). Books that are gone or re-identified are reported per book.
 *
 * Logs carry the task ID, stage, round, book count, outcome and duration; never titles, paths, URLs,
 * tokens or database content (R33).
 */
class ReadStatusWriteTaskHandler(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val queue: DurableTaskQueue,
    private val sources: LibrarySources,
    private val filesDir: File,
    private val io: CoroutineDispatcher,
    private val staging: ReadStatusStaging = ReadStatusStaging(),
    private val requestSync: suspend (TaskOrigin) -> TaskId? = { null },
    private val log: (String) -> Unit = { android.util.Log.i("ReadStatusWrite", it) },
) : TaskHandler {
    private class Prepared(val generation: UUID, val staged: StagedDatabase, val failures: List<BookFailure>, val books: Int)
    private val prepared = ConcurrentHashMap<TaskId, Prepared>()

    override fun supports(request: TaskRequest) = request is TaskRequest.ReadStatusWrite

    override fun controls(stage: TaskStage) =
        if (stage == TaskStage.WRITE_COMMIT) DurableTaskQueue.noControls else TaskControls(true, true, false, false)

    override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision = withContext(io) {
        execution.checkControl()
        discardRound(entry.record.id)
        RecoveryDecision(TaskStage.WRITE_SNAPSHOT, null)
    }

    override suspend fun stopped(entry: QueueEntry) = withContext(io) { discardRound(entry.record.id) }

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome = withContext(io) {
        val id = entry.record.id
        val started = System.nanoTime()
        var books: Int? = null
        var outcome: StageOutcome? = null
        try {
            val request = entry.record.submission.request as TaskRequest.ReadStatusWrite
            val location = state.current()?.identity?.takeIf { it.id == request.libraryId }?.location
                ?: return@withContext StageOutcome.Wait(WaitingReason.INACTIVE_LIBRARY).also { outcome = it }
            val source = sources.of(location)
            outcome = try {
                when (entry.stage) {
                    TaskStage.WRITE_SNAPSHOT -> snapshot(entry, execution, location, source)
                    TaskStage.WRITE_PREPARE -> {
                        val changes = queue.readStatusChanges(id)
                        books = changes.size
                        prepare(entry, execution, request, changes)
                    }
                    TaskStage.WRITE_COMMIT -> {
                        val round = prepared.remove(id)?.takeIf { it.generation == entry.checkpoint?.generation }
                            ?: throw IllegalStateException("A push is only reached from the round's own preparation")
                        books = round.books
                        when (source.pushDatabase(location, round.staged.file, round.staged.sha256, entry.checkpoint!!.version!!, journal(id))) {
                            is PushOutcome.Pushed -> complete(entry, round.failures)
                            PushOutcome.Conflict -> StageOutcome.Retry(TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT)))
                        }
                    }
                    else -> throw IllegalStateException("Not a write stage")
                }
            } catch (failure: SourceFailure) {
                val error = TaskError.Source(failure.error)
                when {
                    // A changed source, a lost connection and an unknown push result all run a new round (R15).
                    failure.transient || failure.error.kind == StorageErrorKind.NO_NETWORK ||
                        failure.error.kind == StorageErrorKind.VERSION_CONFLICT -> StageOutcome.Retry(error, failure.retryDelayMillis)
                    else -> authorizationWait(source, failure.error.kind)?.let { StageOutcome.Wait(it) } ?: StageOutcome.Fail(error)
                }
            }
            outcome!!
        } finally {
            // Only an advance keeps the round's files for its next stage; every other exit starts afresh.
            if (outcome !is StageOutcome.Advance) {
                prepared.remove(id)
                discardRound(id)
            }
            log("task=${id.value} stage=${entry.stage.code} round=${entry.attempts + 1} books=${books ?: "-"} " +
                "outcome=${describe(outcome)} elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    private suspend fun snapshot(entry: QueueEntry, execution: TaskExecution, location: LibraryLocation, source: LibrarySource): StageOutcome {
        val id = entry.record.id
        source.finishPendingPush(location, journal(id))
        execution.checkControl()
        val snapshot = requireNotNull(source.acquireSnapshot(location, id.value, null, execution::checkControl))
        val generation = UUID.randomUUID()
        val base = roundFile(id, generation, "base.db")
        try {
            if (!(base.parentFile!!.mkdirs() || base.parentFile!!.isDirectory) || !snapshot.file.renameTo(base)) throw IOException()
        } finally { snapshot.file.delete() }
        execution.checkpoint(RecoveryCheckpoint(generation, snapshot.version))
        return StageOutcome.Advance(TaskStage.WRITE_PREPARE)
    }

    private suspend fun prepare(entry: QueueEntry, execution: TaskExecution, request: TaskRequest.ReadStatusWrite,
        changes: Map<io.github.chenxiex.calibrecloud.model.BookKey, Boolean>): StageOutcome {
        val id = entry.record.id
        val checkpoint = requireNotNull(entry.checkpoint)
        val preference = metadata.readStatusPreference(request.libraryId)
        // Switching the read column never lets an older task write the new one (R14).
        if (preference?.column != request.column) return StageOutcome.Fail(TaskError.InvalidColumn)
        if (changes.isEmpty()) return StageOutcome.Complete()
        val base = roundFile(id, checkpoint.generation, "base.db")
        val result = try {
            staging.stage(base, roundFile(id, checkpoint.generation, "staged.db"), preference.libraryUuid, request.column, changes) {
                runBlocking { execution.checkControl() }
            }
        } catch (failure: ReadStatusStagingException) {
            return when (failure.reason) {
                StagingFailure.COLUMN_INVALID -> StageOutcome.Fail(TaskError.InvalidColumn)
                StagingFailure.IO -> StageOutcome.Retry(sourceError(StorageErrorKind.LOCAL_IO))
                else -> StageOutcome.Fail(sourceError(when (failure.reason) {
                    StagingFailure.CORRUPT -> StorageErrorKind.CORRUPT_CONTENT
                    StagingFailure.INSUFFICIENT_SPACE -> StorageErrorKind.INSUFFICIENT_SPACE
                    StagingFailure.NO_WRITABLE_BOOKS -> StorageErrorKind.SOURCE_MISSING
                    else -> StorageErrorKind.INCOMPATIBLE_DATABASE
                }))
            }
        } finally { base.delete() }
        val failures = result.books.mapNotNull { (book, outcome) ->
            when (outcome) {
                StagedBookOutcome.MISSING -> BookFailure(book, sourceError(StorageErrorKind.SOURCE_MISSING))
                StagedBookOutcome.IDENTITY_CHANGED -> BookFailure(book, TaskError.BookIdentityChanged(book))
                else -> null
            }
        }
        val staged = result.staged ?: return complete(entry, failures)
        prepared[id] = Prepared(checkpoint.generation, staged, failures, changes.size)
        return StageOutcome.Advance(TaskStage.WRITE_COMMIT)
    }

    private suspend fun complete(entry: QueueEntry, failures: List<BookFailure>) = StageOutcome.Complete(
        if (failures.isEmpty()) TaskResult.Completed else TaskResult.CompletedWithBookFailures(FrozenSet(failures)),
        followUp = requestSync(entry.record.effectiveOrigin))

    private fun describe(outcome: StageOutcome?) = when (outcome) {
        null -> "stopped"
        is StageOutcome.Advance -> "advance:${outcome.stage.code}"
        is StageOutcome.Complete -> if (outcome.result is TaskResult.CompletedWithBookFailures) "completed_with_book_failures" else "completed"
        is StageOutcome.Fail -> "failed:${errorCode(outcome.error)}"
        is StageOutcome.Retry -> "retry:${errorCode(outcome.error)}"
        is StageOutcome.Wait -> "wait:${outcome.reason.code}"
        is StageOutcome.AwaitSync -> "await_sync"
    }
    private fun errorCode(error: TaskError) = when (error) {
        is TaskError.Source -> error.error.kind.name.lowercase()
        TaskError.InvalidColumn -> "invalid_column"
        is TaskError.BookIdentityChanged -> "book_identity_changed"
    }
    private fun sourceError(kind: StorageErrorKind) = TaskError.Source(StorageError(kind))

    private fun directory(id: TaskId) = File(filesDir, "write-staging/${id.value}")
    private fun roundFile(id: TaskId, generation: UUID, name: String) = File(directory(id), "$generation.$name")

    /** Deletes the round's files, never following links; the push journal stays for the next round. */
    private fun discardRound(id: TaskId) {
        directory(id).listFiles().orEmpty().forEach { file ->
            if (file.name != JOURNAL && !Files.isSymbolicLink(file.toPath()) && file.isFile) file.delete()
        }
    }

    private fun journal(id: TaskId) = object : PushJournal {
        private val file = File(directory(id), JOURNAL)
        override suspend fun read(): String? = withContext(io) { if (file.isFile) file.readText() else null }
        override suspend fun write(value: String?) = withContext(io) {
            val directory = file.parentFile!!
            if (value == null) {
                if (!file.delete()) return@withContext
            } else {
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException()
                val temporary = File(directory, "$JOURNAL.tmp")
                FileOutputStream(temporary).use { output -> output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync() }
                if (!temporary.renameTo(file)) throw IOException()
            }
            syncDirectory(directory)
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }

    private companion object { const val JOURNAL = "push.json" }
}
