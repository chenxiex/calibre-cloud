package io.github.chenxiex.calibrecloud.tasks.persistence

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.UUID

/** Opaque private staging generation, never a path or URL. Handlers must revalidate it on recovery. */
data class RecoveryCheckpoint(val generation: UUID, val version: FileVersion?)
data class QueueEntry(
    val record: TaskRecord,
    val stage: TaskStage,
    val attempts: Int = 0,
    val retryAt: Long = 0,
    val checkpoint: RecoveryCheckpoint? = null,
    val recoveryRequired: Boolean = false,
    val control: TaskControl? = null,
)
enum class TaskControl(val code: String) { PAUSE("pause"), CANCEL("cancel"), RESUME("resume"), RETRY("retry") }

/**
 * Transactions own deduplication, sequence allocation, dependency edges and all state transitions.
 * The process-owned coordinator lock is shared by every wakeup using this repository. Events are
 * invalidations emitted after commit: observers always reread persisted state, including after restart.
 * No source access occurs here. Millisecond retry deadlines are supplied by the execution clock.
 */
class DurableTaskQueue(private val database: ApplicationStateDatabase, private val io: CoroutineDispatcher) : TaskQueue {
    internal val executionLock = Mutex()
    private val revision = MutableStateFlow(0L)
    private val eventBus = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 64)
    override val events: Flow<TaskEvent> = eventBus.asSharedFlow()
    override fun observe(taskId: TaskId): Flow<TaskRecord> = revision.mapNotNull { get(taskId)?.record }.distinctUntilChanged()

    suspend fun get(id: TaskId): QueueEntry? = withContext(io) { entries(database.readableDatabase).find { it.record.id == id } }
    internal suspend fun isActive(record: TaskRecord): Boolean = withContext(io) { active(database.readableDatabase, record) }

    suspend fun list(): List<QueueEntry> = withContext(io) { entries(database.readableDatabase).sortedWith { a, b -> compareSchedulingPositions(a.record.scheduling, b.record.scheduling) } }

    override suspend fun submit(submission: TaskSubmission): SubmissionResult = withContext(io) {
        val changed = mutableListOf<TaskRecord>()
        val result = transaction {
            val all = entries(this)
            val byId = all.associateBy { it.record.id }
            val request = submission.request
            if (request.libraryId != null) {
                val backend = rawQuery("SELECT backend FROM library_bindings WHERE library_id = ?", arrayOf(request.libraryId!!.value.toString())).use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: return@transaction rejected()
                if (request is TaskRequest.FormatCopy && backend != backendCode(request.resource.source.backend)) return@transaction rejected()
            } else if (!activeCandidate(this, request)) return@transaction rejected()
            val dependencies = submission.dependencies.toMutableSet()
            if (request is TaskRequest.ReadStatusWrite) {
                all.filter { it.record.submission.request is TaskRequest.ReadStatusWrite && !safeTerminal(it.record) }.forEach {
                    val previous = it.record.submission.request as TaskRequest.ReadStatusWrite
                    if (previous.relatedWriteKeys().any { key -> key in request.relatedWriteKeys() } && previous != request) {
                        dependencies.add(TaskDependency(it.record.id, DependencyRequirement.SAFE_TERMINAL))
                    }
                }
            }
            if (dependencies.any { edge ->
                    val parent = byId[edge.taskId]?.record
                    parent == null || parent.libraryId != request.libraryId ||
                        request.libraryId == null ||
                        (edge.requirement == DependencyRequirement.SOURCE_COMMIT_CONFIRMED &&
                            (parent.submission.request !is TaskRequest.ReadStatusWrite ||
                                (request as? TaskRequest.MetadataSync)?.freshness != SnapshotFreshness.AfterWrite(parent.id)))
                }) return@transaction rejected()
            val normalized = submission.copy(dependencies = FrozenSet(dependencies))
            val existing = all.find { it.record.state !is TaskState.Finished && it.record.submission.key == normalized.key }
            if (existing != null) {
                if (existing.record.scheduling.priority == TaskPriority.LOW && submission.origin.priority == TaskPriority.HIGH) {
                    promote(this, existing.record.id, submission.origin, changed, mutableSetOf())
                    val promoted = changed.last { it.id == existing.record.id }
                    return@transaction SubmissionResult.Promoted(promoted.id, requireNotNull(promoted.promotion))
                }
                return@transaction SubmissionResult.Reused(existing.record.id)
            }
            // New nodes point only to existing nodes. This makes cycles impossible without an edge-edit API.
            val id = TaskId(UUID.randomUUID())
            val record = TaskRecord(id, normalized, SchedulingPosition(submission.origin.priority, sequence(this)),
                state = TaskState.Queued, controls = queuedControls)
            save(this, QueueEntry(record, initialStage(request)))
            dependencies.forEach { edge -> execSQL("INSERT INTO task_dependencies VALUES(?, ?, ?)",
                arrayOf(id.value.toString(), edge.taskId.value.toString(), edge.requirement.code)) }
            if (submission.origin.priority == TaskPriority.HIGH) dependencies.forEach {
                promote(this, it.taskId, submission.origin, changed, mutableSetOf())
            }
            changed.add(record)
            SubmissionResult.Created(id)
        }
        changed.forEach { publish(it) }
        result
    }

    /** Called once under the shared execution lock before dispatch; Running never implies success. */
    internal suspend fun recover() = mutateAll { entry ->
        if (entry.record.state is TaskState.Running) entry.copy(
            record = entry.record.copy(state = TaskState.Queued, controls = queuedControls),
            recoveryRequired = true,
            stage = when (entry.record.commit) {
                is CommitState.Unknown -> TaskStage.RECOVERY_CHECK
                CommitState.Confirmed -> if (entry.record.submission.request is TaskRequest.ReadStatusWrite) TaskStage.WRITE_REFETCH else entry.stage
                CommitState.NotCommitted -> entry.stage
            },
        ) else entry
    }

    /** Eligibility is evaluated before sorting, within the claim transaction. Unknown/confirmed writes reserve execution. */
    internal suspend fun claim(now: Long, conditions: (TaskRecord) -> Set<WaitingReason>, supported: (TaskRequest) -> Boolean, excluded: Set<TaskId> = emptySet()): QueueEntry? = withContext(io) {
        val changed = mutableListOf<TaskRecord>()
        val claimed = transaction {
            val all = entries(this)
            if (all.any { it.record.state is TaskState.Running }) return@transaction null
            val reserved = all.firstOrNull { it.record.submission.request is TaskRequest.ReadStatusWrite &&
                it.record.commit != CommitState.NotCommitted && !safeTerminal(it.record) && active(this, it.record) }
            val eligible = mutableListOf<QueueEntry>()
            all.forEach { entry ->
                val record = entry.record
                if (record.state is TaskState.Finished || record.state is TaskState.Paused || record.id in excluded) return@forEach
                val reasons = conditions(record).toMutableSet()
                if (!active(this, record)) reasons.add(WaitingReason.INACTIVE_LIBRARY)
                if (!supported(record.submission.request)) reasons.add(WaitingReason.DEPENDENCY)
                if (entry.retryAt > now) reasons.add(WaitingReason.NETWORK)
                if (record.submission.dependencies.any { edge ->
                        val parent = all.find { it.record.id == edge.taskId }?.record
                        parent == null || !satisfies(parent, edge.requirement)
                    }) reasons.add(WaitingReason.DEPENDENCY)
                val after = (record.submission.request as? TaskRequest.MetadataSync)?.freshness as? SnapshotFreshness.AfterWrite
                if (reserved != null && record.id != reserved.record.id && after?.writeTaskId != reserved.record.id) reasons.add(WaitingReason.RECOVERY)
                if (reasons.isEmpty()) eligible.add(entry)
                else if (record.state != TaskState.Waiting(FrozenSet(reasons))) {
                    val waiting = entry.copy(record = record.copy(state = TaskState.Waiting(FrozenSet(reasons)), controls = queuedControls))
                    save(this, waiting); changed.add(waiting.record)
                }
            }
            val next = eligible.minWithOrNull { a, b -> compareSchedulingPositions(a.record.scheduling, b.record.scheduling) } ?: return@transaction null
            val running = next.copy(record = next.record.copy(state = TaskState.Running(next.stage), controls = TaskControls(false, false, false, false)))
            save(this, running); changed.add(running.record)
            if (running.record.submission.request is TaskRequest.ReadStatusWrite) {
                val current = entries(this)
                current.filter { it.record.controls.canRetry && retrySuperseded(it, current) }.forEach { old ->
                    val superseded = old.copy(record = old.record.copy(controls = old.record.controls.copy(canRetry = false)))
                    save(this, superseded); changed.add(superseded.record)
                }
            }
            running
        }
        changed.forEach { publish(it) }
        claimed
    }

    suspend fun control(id: TaskId, command: TaskControl): Boolean = withContext(io) {
        var changed: TaskRecord? = null
        val accepted = transaction {
            val entry = entries(this).find { it.record.id == id } ?: return@transaction false
            val r = entry.record
            val allowed = when (command) {
                TaskControl.PAUSE -> r.controls.canPause
                TaskControl.CANCEL -> r.controls.canCancel
                TaskControl.RESUME -> r.controls.canResume
                TaskControl.RETRY -> r.controls.canRetry
            }
            if (!allowed || (command == TaskControl.RETRY && retrySuperseded(entry, entries(this)))) return@transaction false
            val updated = when {
                r.state is TaskState.Running -> entry.copy(control = command)
                command == TaskControl.CANCEL -> entry.copy(record = r.copy(state = TaskState.Finished(TaskResult.Cancelled(r.commit)), controls = TaskControls(false, false, false, r.commit != CommitState.NotCommitted)), control = null)
                else -> entry.copy(record = r.copy(state = TaskState.Queued, controls = queuedControls), control = null,
                    recoveryRequired = true, retryAt = 0, attempts = 0, stage = when (r.commit) {
                        is CommitState.Unknown -> TaskStage.RECOVERY_CHECK
                        CommitState.Confirmed -> TaskStage.WRITE_REFETCH
                        CommitState.NotCommitted -> entry.stage
                    })
            }
            if (command == TaskControl.CANCEL && r.submission.request is TaskRequest.CandidateConfiguration) {
                execSQL("UPDATE current_selection SET token = ? WHERE singleton = 1 AND token = ?", arrayOf(
                    UUID.randomUUID().toString(), r.submission.request.context.selectionToken.toString()))
            }
            save(this, updated); changed = updated.record
            true
        }
        changed?.let { publish(it) }
        accepted
    }

    /** Internal handler boundary: persists before returning, preserving concurrent promotion/control. */
    internal suspend fun update(id: TaskId, cachePublished: Boolean = false, action: (QueueEntry) -> QueueEntry): QueueEntry = withContext(io) {
        val updated = transaction {
            val old = requireNotNull(entries(this).find { it.record.id == id })
            action(old).also { next ->
                require(next.record.id == old.record.id && next.record.submission == old.record.submission)
                require(next.record.scheduling == old.record.scheduling && next.record.promotion == old.record.promotion)
                save(this, next)
            }
        }
        publish(updated.record)
        if (cachePublished) eventBus.tryEmit(TaskEvent.CacheChanged(id, updated.record.submission.request))
        updated
    }

    private suspend fun mutateAll(action: (QueueEntry) -> QueueEntry) = withContext(io) {
        val changed = transaction { entries(this).mapNotNull { old -> action(old).takeIf { it != old }?.also { save(this, it) } } }
        changed.forEach { publish(it.record) }
    }

    private fun activeCandidate(db: SQLiteDatabase, request: TaskRequest): Boolean {
        val candidate = request as? TaskRequest.CandidateConfiguration ?: return false
        return db.rawQuery("SELECT token, backend, authorization_id FROM current_selection WHERE singleton = 1", null).use {
            it.moveToFirst() && it.getString(0) == candidate.context.selectionToken.toString() &&
                it.getString(1) == backendCode(candidate.context.backend) &&
                (it.isNull(2) || it.getString(2) == candidate.context.authorizationId.toString())
        }
    }

    private fun active(db: SQLiteDatabase, record: TaskRecord): Boolean = db.rawQuery("SELECT token, backend, library_id, authorization_id FROM current_selection WHERE singleton = 1", null).use {
        if (!it.moveToFirst()) false else when (val request = record.submission.request) {
            is TaskRequest.CandidateConfiguration -> it.getString(0) == request.context.selectionToken.toString() &&
                it.getString(1) == backendCode(request.context.backend) &&
                (it.isNull(3) || it.getString(3) == request.context.authorizationId.toString())
            else -> it.getString(2) == record.libraryId?.value.toString()
        }
    }

    /** A safe failed intent cannot be replayed after a later overlapping intent has begun. */
    private fun retrySuperseded(entry: QueueEntry, all: List<QueueEntry>): Boolean {
        val write = entry.record.submission.request as? TaskRequest.ReadStatusWrite ?: return false
        if (entry.record.commit != CommitState.NotCommitted) return false
        val index = all.indexOfFirst { it.record.id == entry.record.id }
        return all.drop(index + 1).any { later ->
            val other = later.record.submission.request as? TaskRequest.ReadStatusWrite
            other != null && write.relatedWriteKeys().any { it in other.relatedWriteKeys() } &&
                (later.recoveryRequired || later.stage != initialStage(other) ||
                    later.record.state is TaskState.Running || later.record.state is TaskState.Paused || later.record.state is TaskState.Finished)
        }
    }

    private fun promote(db: SQLiteDatabase, id: TaskId, origin: TaskOrigin, changed: MutableList<TaskRecord>, visited: MutableSet<TaskId>) {
        if (!visited.add(id)) return
        val entry = entries(db).first { it.record.id == id }
        entry.record.submission.dependencies.forEach { promote(db, it.taskId, origin, changed, visited) }
        if (entry.record.state !is TaskState.Finished && entry.record.scheduling.priority == TaskPriority.LOW) {
            val promotion = PriorityPromotion(origin, sequence(db))
            val record = entry.record.copy(scheduling = SchedulingPosition(TaskPriority.HIGH, promotion.sequence), promotion = promotion)
            save(db, entry.copy(record = record)); changed.add(record)
        }
    }

    private fun sequence(db: SQLiteDatabase): QueueSequence {
        val next = db.rawQuery("SELECT next_value FROM queue_sequence WHERE singleton = 1", null).use { it.moveToFirst(); it.getLong(0) }
        check(next < Long.MAX_VALUE)
        db.execSQL("UPDATE queue_sequence SET next_value = ? WHERE singleton = 1", arrayOf(next + 1))
        return QueueSequence(next)
    }

    private fun entries(db: SQLiteDatabase): List<QueueEntry> = db.rawQuery("SELECT record, stage, attempts, retry_at, checkpoint, checkpoint_backend, checkpoint_version, recovery_required, control FROM queued_tasks ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(QueueEntry(TaskCodec.decode(c.getString(0)), TaskStage.entries.first { it.code == c.getString(1) },
            c.getInt(2), c.getLong(3), if (c.isNull(4)) null else RecoveryCheckpoint(UUID.fromString(c.getString(4)),
                if (c.isNull(5)) null else FileVersion(BackendKind.entries.first { backendCode(it) == c.getString(5) }, c.getString(6))),
            c.getInt(7) != 0, if (c.isNull(8)) null else TaskControl.entries.first { it.code == c.getString(8) })) }
    }

    private fun save(db: SQLiteDatabase, entry: QueueEntry) {
        val values = ContentValues().apply {
            put("task_id", entry.record.id.value.toString()); put("record", TaskCodec.encode(entry.record)); put("stage", entry.stage.code)
            put("attempts", entry.attempts); put("retry_at", entry.retryAt); put("recovery_required", if (entry.recoveryRequired) 1 else 0)
            put("checkpoint", entry.checkpoint?.generation?.toString()); put("checkpoint_backend", entry.checkpoint?.version?.backend?.let(::backendCode))
            put("checkpoint_version", entry.checkpoint?.version?.token); put("control", entry.control?.code)
        }
        if (db.update("queued_tasks", values, "task_id = ?", arrayOf(entry.record.id.value.toString())) == 0) db.insertOrThrow("queued_tasks", null, values)
    }
    private fun <T> transaction(action: SQLiteDatabase.() -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        try { return db.action().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun publish(record: TaskRecord) { revision.update { it + 1 }; eventBus.tryEmit(TaskEvent.Changed(record)) }
    private fun rejected() = SubmissionResult.Rejected(TaskError.Source(StorageError(StorageErrorKind.UNSUPPORTED_OPERATION)))
    private fun backendCode(kind: BackendKind) = when (kind) { BackendKind.LOCAL -> "local"; BackendKind.ONEDRIVE -> "onedrive" }

    companion object {
        internal val queuedControls = TaskControls(false, true, false, false)
        internal val noControls = TaskControls(false, false, false, false)
        fun initialStage(request: TaskRequest): TaskStage = when (request) {
            is TaskRequest.CandidateConfiguration -> TaskStage.CANDIDATE_ACCESS
            is TaskRequest.MetadataSync -> TaskStage.METADATA_FETCH
            is TaskRequest.FormatCopy -> TaskStage.FORMAT_TRANSFER
            is TaskRequest.CoverLoad -> TaskStage.COVER_TRANSFER
            is TaskRequest.ReadStatusWrite -> TaskStage.WRITE_SNAPSHOT
        }
        fun safeTerminal(record: TaskRecord): Boolean {
            val result = (record.state as? TaskState.Finished)?.result ?: return false
            return record.commit == CommitState.NotCommitted || result == TaskResult.Completed || result is TaskResult.CompletedWithBookFailures
        }
        private fun satisfies(record: TaskRecord, requirement: DependencyRequirement): Boolean = when (requirement) {
            DependencyRequirement.SAFE_TERMINAL -> safeTerminal(record)
            DependencyRequirement.SOURCE_COMMIT_CONFIRMED -> record.commit == CommitState.Confirmed
            DependencyRequirement.SUCCESS -> (record.state as? TaskState.Finished)?.result.let { it == TaskResult.Completed || it is TaskResult.CompletedWithBookFailures }
        }
    }
}
