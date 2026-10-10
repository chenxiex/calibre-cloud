package io.github.chenxiex.calibrecloud.tasks.persistence

import io.github.chenxiex.calibrecloud.storage.api.LocationKeys
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.storage.api.CalibreStamp
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
    /**
     * Metadata sync submitted for this task's stale OneDrive path; set at most once until user retry (R11).
     * Stored as a dependency edge added at run time, satisfied once the sync finishes with any result.
     */
    val sourceSync: TaskId? = null,
    /** The path that was not found; after the sync an unchanged path fails without another request. */
    val missingPath: RelativeSourcePath? = null,
    /** Set when the task is first claimed; a read status write's change list is fixed from then on (R14). */
    val started: Boolean = false,
    /** The sync a completed read status write submitted right after its push (R16). */
    val followUp: TaskId? = null,
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
    internal var beforeDispatch: suspend () -> Unit = {}
    /** Installed by the process container; called after durable submission/control, never inside a transaction. */
    var onWake: suspend () -> Unit = {}
    private val revision = MutableStateFlow(0L)
    /** Raised by every accepted submission and control; a running task's safe boundary rereads the queue only after a raise. */
    private val requests = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var noHighPendingAt = 0L
    private val eventBus = MutableSharedFlow<TaskEvent>(extraBufferCapacity = 64)
    override val events: Flow<TaskEvent> = eventBus.asSharedFlow()
    override fun observe(taskId: TaskId): Flow<TaskRecord> = revision.mapNotNull { get(taskId)?.record }.distinctUntilChanged()

    suspend fun get(id: TaskId): QueueEntry? = withContext(io) { entry(database.readableDatabase, id) }
    internal suspend fun isActive(record: TaskRecord): Boolean = withContext(io) { active(database.readableDatabase, record) }

    /** Library that was current when a candidate was submitted, or that its import activated. */
    internal suspend fun scopeLibrary(id: TaskId): LibraryId? = withContext(io) {
        database.readableDatabase.rawQuery("SELECT scope_library_id FROM queued_tasks WHERE task_id = ?", arrayOf(id.value.toString())).use {
            if (it.moveToFirst() && !it.isNull(0)) LibraryId(UUID.fromString(it.getString(0))) else null
        }
    }

    suspend fun list(): List<QueueEntry> = withContext(io) { entries(database.readableDatabase).sortedWith { a, b -> compareSchedulingPositions(a.record.scheduling, b.record.scheduling) } }

    override suspend fun submit(submission: TaskSubmission): SubmissionResult = withContext(io) {
        val changed = mutableListOf<TaskRecord>()
        val result = transaction {
            if (cleanupBlocks(this, submission.request)) return@transaction rejected()
            val all = entries(this)
            val byId = all.associateBy { it.record.id }
            val request = submission.request
            if (request.libraryId != null) {
                val backend = rawQuery("SELECT backend FROM library_bindings WHERE library_id = ?", arrayOf(request.libraryId!!.value.toString())).use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: return@transaction rejected()
                if (request is TaskRequest.FormatCopy && backend != backendCode(request.resource.source.backend)) return@transaction rejected()
            } else if (!holds(this, request.owner)) return@transaction rejected()
            // A write's change list only enters through submitReadStatus.
            if (request is TaskRequest.ReadStatusWrite) return@transaction rejected()
            val dependencies = submission.dependencies
            if (dependencies.any { edge ->
                    val parent = byId[edge.taskId]?.record
                    parent == null || parent.libraryId != request.libraryId || request.libraryId == null
                }) return@transaction rejected()
            val normalized = submission
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
            if (request is TaskRequest.CandidateConfiguration) execSQL("""UPDATE queued_tasks SET scope_library_id =
                (SELECT library_id FROM current_selection WHERE singleton = 1 AND token = ?) WHERE task_id = ?""",
                arrayOf(request.context.selectionToken.toString(), id.value.toString()))
            dependencies.forEach { edge -> execSQL("INSERT INTO task_dependencies VALUES(?, ?, ?)",
                arrayOf(id.value.toString(), edge.taskId.value.toString(), edge.requirement.code)) }
            if (submission.origin.priority == TaskPriority.HIGH) dependencies.forEach {
                promote(this, it.taskId, submission.origin, changed, mutableSetOf())
            }
            changed.add(record)
            SubmissionResult.Created(id)
        }
        changed.forEach { publish(it) }
        if (result !is SubmissionResult.Rejected) {
            requests.incrementAndGet()
            onWake()
        }
        result
    }

    /**
     * Adds a click's read status [changes] (book to explicit target) for [column] of [libraryId] (R14),
     * in one transaction. The library's unfinished write to [column] that has not started takes them,
     * the last target of a book winning, and is reused; otherwise a new write is created after every
     * unfinished write of the library (FINISHED edges). Claiming marks a task started in its own
     * transaction, so a change joins a list before that task runs or goes to a new one, never in between.
     * The same books leave the library's failed or cancelled writes to [column]: the newer marking
     * replaces that intent, so a retry of the old task cannot write it back.
     */
    suspend fun submitReadStatus(libraryId: LibraryId, column: CustomColumnId, changes: Map<BookKey, Boolean>,
        origin: TaskOrigin): SubmissionResult = withContext(io) {
        require(changes.isNotEmpty() && changes.keys.all { it.libraryId == libraryId })
        val changed = mutableListOf<TaskRecord>()
        val result = transaction {
            if (cleanupBlocks(this, TaskRequest.ReadStatusWrite(libraryId, column, UUID(0, 0)))) return@transaction rejected()
            val bound = rawQuery("SELECT 1 FROM library_bindings WHERE library_id = ?", arrayOf(libraryId.value.toString())).use { it.moveToFirst() }
            if (!bound) return@transaction rejected()
            val writes = entries(this).filter { it.record.submission.request is TaskRequest.ReadStatusWrite && it.record.libraryId == libraryId }
            val unfinished = writes.filter { it.record.state !is TaskState.Finished && !isRevoked(this, it.record.id) }
            val open = unfinished.firstOrNull { !it.started && (it.record.submission.request as TaskRequest.ReadStatusWrite).column == column }
            val id = if (open != null) open.record.id.also { changed.add(open.record) } else {
                val id = TaskId(UUID.randomUUID())
                val dependencies = unfinished.map { TaskDependency(it.record.id, DependencyRequirement.FINISHED) }
                val record = TaskRecord(id, TaskSubmission(TaskRequest.ReadStatusWrite(libraryId, column, UUID.randomUUID()), origin,
                    FrozenSet(dependencies)), SchedulingPosition(origin.priority, sequence(this)), state = TaskState.Queued, controls = queuedControls)
                save(this, QueueEntry(record, initialStage(record.submission.request)))
                dependencies.forEach { edge -> execSQL("INSERT INTO task_dependencies VALUES(?, ?, ?)",
                    arrayOf(id.value.toString(), edge.taskId.value.toString(), edge.requirement.code)) }
                changed.add(record)
                id
            }
            val replaced = writes.filter { entry ->
                val result = (entry.record.state as? TaskState.Finished)?.result
                (result is TaskResult.Failed || result == TaskResult.Cancelled) &&
                    (entry.record.submission.request as TaskRequest.ReadStatusWrite).column == column
            }
            changes.forEach { (book, target) ->
                val bookArgs = arrayOf(book.sourceId.toString(), book.sourceUuid.toString())
                replaced.forEach { delete("read_status_changes", "task_id = ? AND book_id = ? AND book_uuid = ?",
                    arrayOf(it.record.id.value.toString()) + bookArgs) }
                execSQL("INSERT OR REPLACE INTO read_status_changes(task_id, book_id, book_uuid, target, dismissed) VALUES(?, ?, ?, ?, 0)",
                    arrayOf<Any>(id.value.toString(), book.sourceId, book.sourceUuid.toString(), if (target) 1 else 0))
            }
            if (open != null) SubmissionResult.Reused(id) else SubmissionResult.Created(id)
        }
        changed.forEach { publish(it) }
        if (result !is SubmissionResult.Rejected) {
            requests.incrementAndGet()
            onWake()
        }
        result
    }

    /** The change list of write [id], in no particular order. */
    suspend fun readStatusChanges(id: TaskId): Map<BookKey, Boolean> = withContext(io) {
        val db = database.readableDatabase
        val library = entry(db, id)?.record?.libraryId ?: return@withContext emptyMap()
        db.rawQuery("SELECT book_id, book_uuid, target FROM read_status_changes WHERE task_id = ?", arrayOf(id.value.toString())).use {
            buildMap { while (it.moveToNext()) put(BookKey(library, it.getLong(0), UUID.fromString(it.getString(1))), it.getInt(2) != 0) }
        }
    }

    /**
     * Books of [libraryId] with a read status target for [column] still being written (R13, R27), in one
     * query. A book's newest row decides: in an unfinished write it is pending; in a completed write it
     * stays pending until the sync that followed ends, whatever its result; in a failed or cancelled
     * write, or as a book failure, it is failed until the next successful sync dismisses it. A retry
     * makes it pending again, and marking the book again adds a newer row.
     */
    suspend fun pendingReadStatus(libraryId: LibraryId, column: CustomColumnId): Map<BookKey, PendingRead> = withContext(io) {
        val db = database.readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val records = mutableMapOf<String, TaskRecord?>()
            fun record(id: String?) = id?.let { records.getOrPut(it) {
                db.rawQuery("SELECT record FROM queued_tasks WHERE task_id = ?", arrayOf(it)).use { c ->
                    if (c.moveToFirst()) TaskCodec.decode(c.getString(0)) else null
                }
            } }
            val newest = mutableMapOf<BookKey, Pair<TaskRecord, PendingRead?>>()
            db.rawQuery("""SELECT c.task_id, q.follow_up_id, c.book_id, c.book_uuid, c.target, c.dismissed
                FROM read_status_changes c JOIN queued_tasks q ON q.task_id = c.task_id""", null).use { c ->
                while (c.moveToNext()) {
                    val write = record(c.getString(0)) ?: continue
                    val request = write.submission.request as? TaskRequest.ReadStatusWrite ?: continue
                    if (request.libraryId != libraryId || request.column != column) continue
                    val book = BookKey(libraryId, c.getLong(2), UUID.fromString(c.getString(3)))
                    val previous = newest[book]?.first
                    if (previous != null && previous.scheduling.sequence.value > write.scheduling.sequence.value) continue
                    val target = c.getInt(4) != 0
                    val dismissed = c.getInt(5) != 0
                    val following = record(if (c.isNull(1)) null else c.getString(1))?.let { it.state !is TaskState.Finished } == true
                    val shown = when (val state = write.state) {
                        !is TaskState.Finished -> PendingRead.Pending(target)
                        else -> when (val result = state.result) {
                            TaskResult.Completed -> if (following) PendingRead.Pending(target) else null
                            is TaskResult.CompletedWithBookFailures -> result.failures.find { it.book == book }
                                ?.let { if (dismissed) null else PendingRead.Failed(target, it.error) }
                                ?: if (following) PendingRead.Pending(target) else null
                            is TaskResult.Failed -> if (dismissed) null else PendingRead.Failed(target, result.failure.error)
                            TaskResult.Cancelled -> if (dismissed) null else PendingRead.Failed(target, null)
                        }
                    }
                    newest[book] = write to shown
                }
            }
            newest.mapNotNull { (book, shown) -> shown.second?.let { book to it } }.toMap()
        } finally { db.endTransaction() }
    }

    /**
     * The library page that showed these failure marks was left (R13): rows of [books] in finished
     * writes of [libraryId] are no longer shown. Unfinished writes are untouched, so a write that
     * fails later still shows its failure; a retry or a new mark shows the book again.
     */
    suspend fun dismissShownReadFailures(libraryId: LibraryId, books: Collection<BookKey>) = withContext(io) {
        if (books.isEmpty()) return@withContext
        transaction {
            val finished = rawQuery("""SELECT DISTINCT q.task_id, q.record FROM queued_tasks q
                JOIN read_status_changes c ON c.task_id = q.task_id WHERE c.dismissed = 0""", null).use { c ->
                buildList { while (c.moveToNext()) {
                    val record = TaskCodec.decode(c.getString(1))
                    if (record.libraryId == libraryId && record.state is TaskState.Finished) add(c.getString(0))
                } }
            }
            finished.forEach { task -> books.filter { it.libraryId == libraryId }.forEach { book ->
                execSQL("UPDATE read_status_changes SET dismissed = 1 WHERE task_id = ? AND book_id = ? AND book_uuid = ?",
                    arrayOf<Any>(task, book.sourceId, book.sourceUuid.toString()))
            } }
        }
    }

    /** Called once under the shared execution lock before dispatch; Running never implies success. */
    internal suspend fun recover() = mutateAll { entry ->
        if (entry.record.state is TaskState.Running) entry.copy(
            record = entry.record.copy(state = TaskState.Queued, controls = queuedControls),
            recoveryRequired = true,
        ) else entry
    }

    /**
     * Eligibility is evaluated before sorting, within the claim transaction. A task recorded as the
     * next one, the sync right after a write (R16), is taken when eligible; either way the record is
     * consumed by this selection.
     */
    internal suspend fun claim(now: Long, conditions: (TaskRecord) -> Set<WaitingReason>, supported: (TaskRequest) -> Boolean, excluded: Set<TaskId> = emptySet()): QueueEntry? = withContext(io) {
        val changed = mutableListOf<TaskRecord>()
        val claimed = transaction {
            val all = entries(this)
            if (all.any { it.record.state is TaskState.Running }) return@transaction null
            execSQL("DELETE FROM source_throttle WHERE until <= ?", arrayOf<Any>(now))
            val edges = edges(this)
            val next = rawQuery("SELECT task_id FROM dispatch_next WHERE singleton = 1", null).use {
                if (it.moveToFirst()) TaskId(UUID.fromString(it.getString(0))) else null
            }
            if (next != null) execSQL("DELETE FROM dispatch_next")
            val eligible = mutableListOf<QueueEntry>()
            all.forEach { entry ->
                val record = entry.record
                if (isRevoked(this, record.id) || cacheCleanupPending(this) || record.state is TaskState.Finished || record.state is TaskState.Paused || record.id in excluded) return@forEach
                val reasons = conditions(record).toMutableSet()
                if (!active(this, record)) {
                    reasons.add(WaitingReason.INACTIVE_LIBRARY)
                    // An inactive task never runs, so keep the authorization reason its handler recorded.
                    (record.state as? TaskState.Waiting)?.reasons?.filter {
                        it == WaitingReason.LOGIN || it == WaitingReason.DIRECTORY_AUTHORIZATION }?.let(reasons::addAll)
                }
                if (!supported(record.submission.request)) reasons.add(WaitingReason.DEPENDENCY)
                // Backoff keeps the reason recorded by the retry: server throttling or an unreachable network.
                if (entry.retryAt > now) reasons.add(if ((record.state as? TaskState.Waiting)?.reasons?.contains(WaitingReason.THROTTLED) == true)
                    WaitingReason.THROTTLED else WaitingReason.NETWORK)
                // A throttled library holds all of its source work until the server's deadline.
                val throttled = throttleScope(this, record)?.let { throttleDeadline(this, it) }?.takeIf { it > now }
                if (throttled != null) reasons.add(WaitingReason.THROTTLED)
                if (edges[record.id].orEmpty().any { (prerequisite, requirement) ->
                        val parent = all.find { it.record.id == prerequisite }?.record
                        parent == null || !satisfies(parent, requirement)
                    }) reasons.add(WaitingReason.DEPENDENCY)
                val retryAt = maxOf(entry.retryAt, throttled ?: 0)
                if (reasons.isEmpty()) eligible.add(entry)
                else if (record.state != TaskState.Waiting(FrozenSet(reasons)) || retryAt != entry.retryAt) {
                    // The deadline doubles as this task's wakeup time for background scheduling.
                    val waiting = entry.copy(retryAt = retryAt, record = record.copy(state = TaskState.Waiting(FrozenSet(reasons)), controls = queuedControls))
                    save(this, waiting); if (waiting.record != record) changed.add(waiting.record)
                }
            }
            val chosen = eligible.find { it.record.id == next }
                ?: eligible.minWithOrNull { a, b -> compareSchedulingPositions(a.record.scheduling, b.record.scheduling) } ?: return@transaction null
            val running = chosen.copy(started = true, record = chosen.record.copy(state = TaskState.Running(chosen.stage), controls = noControls))
            save(this, running); changed.add(running.record)
            running
        }
        changed.forEach { publish(it) }
        claimed
    }

    suspend fun control(id: TaskId, command: TaskControl): Boolean = withContext(io) {
        var changed: TaskRecord? = null
        val accepted = transaction {
            val entry = entry(this, id) ?: return@transaction false
            if (isRevoked(this, id)) return@transaction false
            val r = entry.record
            val allowed = when (command) {
                TaskControl.PAUSE -> r.controls.canPause
                TaskControl.CANCEL -> r.controls.canCancel
                TaskControl.RESUME -> r.controls.canResume
                TaskControl.RETRY -> r.controls.canRetry
            }
            if (!allowed) return@transaction false
            val updated = when {
                r.state is TaskState.Running -> entry.copy(control = command)
                command == TaskControl.CANCEL -> entry.copy(record = r.copy(state = TaskState.Finished(TaskResult.Cancelled), controls = noControls), control = null)
                else -> entry.copy(record = r.copy(state = TaskState.Queued, controls = queuedControls), control = null,
                    recoveryRequired = true, retryAt = 0, attempts = 0,
                    sourceSync = if (command == TaskControl.RETRY) null else entry.sourceSync,
                    missingPath = if (command == TaskControl.RETRY) null else entry.missingPath)
            }
            // A retried write shows its books as pending again, even after an import dismissed the failure.
            if (command == TaskControl.RETRY) execSQL("UPDATE read_status_changes SET dismissed = 0 WHERE task_id = ?", arrayOf(id.value.toString()))
            if (command == TaskControl.CANCEL && r.submission.request is TaskRequest.CandidateConfiguration) {
                execSQL("UPDATE current_selection SET token = ? WHERE singleton = 1 AND token = ?", arrayOf(
                    UUID.randomUUID().toString(), r.submission.request.context.selectionToken.toString()))
            }
            save(this, updated); changed = updated.record
            true
        }
        changed?.let { publish(it) }
        if (accepted) {
            requests.incrementAndGet()
            onWake()
        }
        accepted
    }

    /**
     * Whether the running task [id] should yield at a safe boundary (R17): it is still low priority and
     * a high-priority request waits in line. Requests whose conditions are unmet show as waiting after
     * the next selection, so a yield for them happens at most once.
     */
    internal suspend fun highPriorityWaiting(id: TaskId): Boolean {
        val seen = requests.get()
        if (seen == noHighPendingAt) return false
        return withContext(io) {
            val all = entries(database.readableDatabase)
            val self = all.find { it.record.id == id }?.record
            val waiting = self?.scheduling?.priority == TaskPriority.LOW && all.any {
                it.record.id != id && it.record.scheduling.priority == TaskPriority.HIGH && it.record.state == TaskState.Queued
            }
            if (!waiting) noHighPendingAt = seen
            waiting
        }
    }

    /** A cover batch of the same library submitted after [id], which replaces it (R10). */
    internal suspend fun newerCoverBatch(id: TaskId): Boolean = withContext(io) {
        val all = entries(database.readableDatabase)
        val self = all.find { it.record.id == id }?.record ?: return@withContext false
        all.any { other -> other.record.submission.request is TaskRequest.CoverLoad && other.record.libraryId == self.libraryId &&
            other.record.scheduling.sequence.value > self.scheduling.sequence.value && other.record.originalOrigin == TaskOrigin.VISIBLE_COVER }
    }

    /**
     * Ends the unfinished automatic cover batches of [libraryId] other than [keep] that have not started;
     * a running one ends itself after its current cover (R10).
     */
    internal suspend fun supersedeCoverBatches(libraryId: io.github.chenxiex.calibrecloud.model.LibraryId, keep: TaskId) = withContext(io) {
        val changed = transaction {
            entries(this).filter { entry ->
                val record = entry.record
                record.id != keep && record.submission.request is TaskRequest.CoverLoad && record.libraryId == libraryId &&
                    record.effectiveOrigin == TaskOrigin.VISIBLE_COVER && record.state !is TaskState.Finished && record.state !is TaskState.Running
            }.map { entry ->
                entry.copy(record = entry.record.copy(state = TaskState.Finished(TaskResult.Cancelled), controls = noControls),
                    control = null, checkpoint = null).also { save(this, it) }.record
            }
        }
        changed.forEach { publish(it) }
    }

    /**
     * Deletes, in the caller's transaction, finished tasks older than the newest [keep] (R18, Q74) and
     * returns them; the caller reclaims their task-keyed private files. A finished task stays while its
     * [TaskOwner] still holds it, while another task depends on it, and while a checkpoint awaits
     * cleanup. A write's change list goes with it.
     */
    internal fun pruneFinished(db: SQLiteDatabase, keep: Int = FINISHED_HISTORY): List<TaskId> {
        val prerequisites = db.rawQuery("SELECT DISTINCT prerequisite_id FROM task_dependencies", null).use {
            buildSet { while (it.moveToNext()) add(TaskId(UUID.fromString(it.getString(0)))) }
        }
        val pruned = entries(db).filter { it.record.state is TaskState.Finished }
            .sortedByDescending { it.record.scheduling.sequence.value }
            .drop(keep)
            .filter { entry ->
                val record = entry.record
                !holds(db, record.submission.request.owner) && record.id !in prerequisites && entry.checkpoint == null
            }.map { it.record.id }
        pruned.forEach { id ->
            val args = arrayOf(id.value.toString())
            db.delete("read_status_changes", "task_id = ?", args)
            db.delete("task_dependencies", "task_id = ?", args)
            db.delete("queued_tasks", "task_id = ?", args)
        }
        return pruned
    }

    /**
     * Internal handler boundary: persists before returning, preserving concurrent promotion/control.
     * With [next], the same transaction records the task the next selection takes first (R16).
     */
    internal suspend fun update(id: TaskId, cachePublished: Boolean = false, next: TaskId? = null,
        action: (QueueEntry) -> QueueEntry): QueueEntry = withContext(io) {
        val updated = transaction {
            val old = requireNotNull(entry(this, id))
            action(old).also { changed ->
                require(changed.record.id == old.record.id && changed.record.submission == old.record.submission)
                require(changed.record.scheduling == old.record.scheduling && changed.record.promotion == old.record.promotion)
                save(this, changed)
                if (next != null) execSQL("INSERT OR REPLACE INTO dispatch_next(singleton, task_id) VALUES(1, ?)", arrayOf(next.value.toString()))
            }
        }
        publish(updated.record)
        if (cachePublished) eventBus.tryEmit(TaskEvent.CacheChanged(id, updated.record.submission.request))
        updated
    }

    /**
     * Records a server throttling deadline for the task's library (or, before an import, its candidate
     * selection), whatever its backend. Other libraries are unaffected; an earlier deadline never
     * shortens a later one. The row survives process death.
     */
    internal suspend fun throttle(id: TaskId, until: Long) = withContext(io) {
        transaction {
            val record = entry(this, id)?.record ?: return@transaction
            val scope = throttleScope(this, record) ?: return@transaction
            execSQL("""INSERT INTO source_throttle(scope, until) VALUES(?, ?)
                ON CONFLICT(scope) DO UPDATE SET until = max(until, excluded.until)""", arrayOf<Any>(scope, until))
        }
    }

    private fun throttleScope(db: SQLiteDatabase, record: TaskRecord): String? {
        val request = record.submission.request
        if (request is TaskRequest.CandidateConfiguration) {
            return db.rawQuery("SELECT scope_library_id FROM queued_tasks WHERE task_id = ?", arrayOf(record.id.value.toString())).use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            } ?: "selection:${request.context.selectionToken}"
        }
        return request.libraryId?.value?.toString()
    }

    private fun throttleDeadline(db: SQLiteDatabase, scope: String): Long? =
        db.rawQuery("SELECT until FROM source_throttle WHERE scope = ?", arrayOf(scope)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private suspend fun mutateAll(action: (QueueEntry) -> QueueEntry) = withContext(io) {
        val changed = transaction { entries(this).mapNotNull { old -> action(old).takeIf { it != old }?.also { save(this, it) } } }
        changed.forEach { publish(it.record) }
    }

    /**
     * Whether [owner] still holds its tasks. A selection holds them while its token is the current
     * selection's or, with [TaskOwner.Selection.includesAddition], the addition's; both under the same
     * backend and authorization. Such a task is also the only one that may run before its library exists.
     */
    private fun holds(db: SQLiteDatabase, owner: TaskOwner): Boolean {
        val selection = owner as? TaskOwner.Selection ?: return false
        val context = selection.context
        fun matches(table: String) = db.rawQuery("SELECT token, backend, authorization_id FROM $table WHERE singleton = 1", null).use {
            it.moveToFirst() && it.getString(0) == context.selectionToken.toString() &&
                it.getString(1) == backendCode(context.backend) &&
                (it.isNull(2) || it.getString(2) == context.authorizationId.toString())
        }
        return matches("current_selection") || (selection.includesAddition && matches("library_addition"))
    }

    private fun active(db: SQLiteDatabase, record: TaskRecord): Boolean = when (val owner = record.submission.request.owner) {
        is TaskOwner.Selection -> holds(db, owner)
        TaskOwner.Queue -> db.rawQuery("SELECT library_id FROM current_selection WHERE singleton = 1", null).use {
            it.moveToFirst() && it.getString(0) == record.libraryId?.value.toString()
        }
    }

    private fun promote(db: SQLiteDatabase, id: TaskId, origin: TaskOrigin, changed: MutableList<TaskRecord>, visited: MutableSet<TaskId>) {
        if (!visited.add(id)) return
        val entry = requireNotNull(entry(db, id))
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

    private fun entry(db: SQLiteDatabase, id: TaskId): QueueEntry? = entries(db, "WHERE task_id = ?", arrayOf(id.value.toString())).singleOrNull()
    private fun entries(db: SQLiteDatabase, where: String = "", args: Array<String>? = null): List<QueueEntry> = db.rawQuery("SELECT record, stage, attempts, retry_at, checkpoint, checkpoint_backend, checkpoint_version, recovery_required, control, (SELECT prerequisite_id FROM task_dependencies d WHERE d.task_id = queued_tasks.task_id AND d.requirement = '$AWAITED_SYNC'), missing_path, started, follow_up_id FROM queued_tasks $where ORDER BY rowid", args).use { c ->
        buildList { while (c.moveToNext()) add(QueueEntry(TaskCodec.decode(c.getString(0)), TaskStage.entries.first { it.code == c.getString(1) },
            c.getInt(2), c.getLong(3), if (c.isNull(4)) null else RecoveryCheckpoint(UUID.fromString(c.getString(4)),
                if (c.isNull(5)) null else FileVersion(BackendKind.entries.first { backendCode(it) == c.getString(5) }, c.getString(6))),
            c.getInt(7) != 0, if (c.isNull(8)) null else TaskControl.entries.first { it.code == c.getString(8) },
            if (c.isNull(9)) null else TaskId(UUID.fromString(c.getString(9))),
            if (c.isNull(10)) null else RelativeSourcePath(c.getString(10)), c.getInt(11) != 0,
            if (c.isNull(12)) null else TaskId(UUID.fromString(c.getString(12))))) }
    }

    private fun save(db: SQLiteDatabase, entry: QueueEntry) {
        val values = ContentValues().apply {
            put("task_id", entry.record.id.value.toString()); put("record", TaskCodec.encode(entry.record)); put("stage", entry.stage.code)
            put("attempts", entry.attempts); put("retry_at", entry.retryAt); put("recovery_required", if (entry.recoveryRequired) 1 else 0)
            put("checkpoint", entry.checkpoint?.generation?.toString()); put("checkpoint_backend", entry.checkpoint?.version?.backend?.let(::backendCode))
            put("checkpoint_version", entry.checkpoint?.version?.token); put("control", entry.control?.code)
            put("missing_path", entry.missingPath?.value)
            put("started", if (entry.started) 1 else 0); put("follow_up_id", entry.followUp?.value?.toString())
        }
        val args = arrayOf(entry.record.id.value.toString())
        if (db.update("queued_tasks", values, "task_id = ?", args) == 0) db.insertOrThrow("queued_tasks", null, values)
        db.delete("task_dependencies", "task_id = ? AND requirement = ?", args + AWAITED_SYNC)
        entry.sourceSync?.let { sync -> db.execSQL("INSERT INTO task_dependencies VALUES(?, ?, ?)",
            arrayOf(entry.record.id.value.toString(), sync.value.toString(), AWAITED_SYNC)) }
    }

    /** Every edge in the queue by dependent task, submitted or added at run time, as (prerequisite, requirement code). */
    private fun edges(db: SQLiteDatabase): Map<TaskId, List<Pair<TaskId, String>>> =
        db.rawQuery("SELECT task_id, prerequisite_id, requirement FROM task_dependencies", null).use {
            buildList { while (it.moveToNext()) add(TaskId(UUID.fromString(it.getString(0))) to
                (TaskId(UUID.fromString(it.getString(1))) to it.getString(2))) }
        }.groupBy({ it.first }, { it.second })
    private fun <T> transaction(action: SQLiteDatabase.() -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        try { return db.action().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    internal fun invalidateObservers() { revision.update { it + 1 } }
    /** Safe-boundary command for one running task, read by primary key without decoding the record. */
    internal suspend fun boundaryControl(id: TaskId): TaskControl? = withContext(io) {
        database.readableDatabase.rawQuery("SELECT revoked, control FROM queued_tasks WHERE task_id = ?", arrayOf(id.value.toString())).use {
            when {
                !it.moveToFirst() -> null
                it.getInt(0) != 0 -> TaskControl.CANCEL
                it.isNull(1) -> null
                else -> TaskControl.entries.first { command -> command.code == it.getString(1) }
            }
        }
    }

    private fun publish(record: TaskRecord) { revision.update { it + 1 }; eventBus.tryEmit(TaskEvent.Changed(record)) }
    private fun rejected() = SubmissionResult.Rejected(TaskError.Source(StorageError(StorageErrorKind.UNSUPPORTED_OPERATION)))
    private fun backendCode(kind: BackendKind) = LocationKeys.backendCode(kind)

    companion object {
        internal fun cacheCleanupPending(db: SQLiteDatabase): Boolean = db.rawQuery("SELECT 1 FROM cache_cleanup LIMIT 1", null).use { it.moveToFirst() }
        /** New requests outside the frozen cleanup scope remain accepted. Dispatch waits for deletion. */
        private fun cleanupBlocks(db: SQLiteDatabase, request: TaskRequest): Boolean = db.rawQuery("SELECT payload FROM cache_cleanup", null).use { rows ->
            while (rows.moveToNext()) {
                val journal = org.json.JSONObject(rows.getString(0))
                val libraries = journal.getJSONArray("libraries")
                val inLibrary = (0 until libraries.length()).any { libraries.getString(it) == request.libraryId?.value?.toString() }
                val candidate = request as? TaskRequest.CandidateConfiguration
                val kind = journal.optString("kind", "OTHER_LIBRARIES")
                val inCandidate = kind == "METADATA" && candidate != null && candidate.context.selectionToken.toString() == journal.optString("token")
                if (!inLibrary && !inCandidate) continue
                if (kind == "METADATA" && request is TaskRequest.ReadStatusWrite) continue
                if (kind == "METADATA") {
                    if (request is TaskRequest.CoverLoad || candidate?.operation == TaskRequest.CandidateConfiguration.LIBRARY_SYNC) return@use true
                } else if (kind == "COPIES") {
                    val key = when (request) {
                        is TaskRequest.FormatCopy -> io.github.chenxiex.calibrecloud.model.CopyKey(request.resource.book, request.resource.format)
                        is TaskRequest.FormatCheck -> request.key
                        else -> null
                    } ?: continue
                    val books = journal.getJSONArray("books")
                    val book = "${key.book.libraryId.value}/${key.book.sourceId}/${key.book.sourceUuid}"
                    if ((0 until books.length()).none { books.getString(it) == book }) continue
                    if (journal.isNull("formats")) return@use true
                    val formats = journal.getJSONArray("formats")
                    if ((0 until formats.length()).any { formats.getString(it) == key.format.value }) return@use true
                } else return@use true
            }
            false
        }

        internal fun isRevoked(db: SQLiteDatabase, id: TaskId): Boolean = db.rawQuery(
            "SELECT revoked FROM queued_tasks WHERE task_id = ?", arrayOf(id.value.toString())).use { it.moveToFirst() && it.getInt(0) != 0 }

        /**
         * Irrevocable cancellation gate, in the same transaction as cache record removal. A withdrawn
         * write's change list goes with it: its library's state is being removed, so no mark remains.
         */
        internal fun revoke(db: SQLiteDatabase, id: TaskId) {
            val args = arrayOf(id.value.toString())
            val record = db.rawQuery("SELECT record FROM queued_tasks WHERE task_id = ?", args).use { it.moveToFirst(); TaskCodec.decode(it.getString(0)) }
            val next = record.copy(state = if ((record.state as? TaskState.Finished)?.result == TaskResult.Completed) record.state
                else TaskState.Finished(TaskResult.Cancelled), controls = noControls)
            db.update("queued_tasks", ContentValues().apply {
                put("revoked", 1); put("control", "cancel"); put("record", TaskCodec.encode(next))
            }, "task_id = ?", args)
            db.delete("read_status_changes", "task_id = ?", args)
        }

        /**
         * Called in the transaction of a successful sync of [libraryId]: failure marks of its finished
         * writes are no longer shown (R13). The change lists stay for a retry, which shows them again.
         */
        internal fun dismissReadFailures(db: SQLiteDatabase, libraryId: io.github.chenxiex.calibrecloud.model.LibraryId) {
            val tasks = db.rawQuery("""SELECT task_id, record FROM queued_tasks
                WHERE task_id IN (SELECT DISTINCT task_id FROM read_status_changes WHERE dismissed = 0)""", null).use { c ->
                buildList { while (c.moveToNext()) {
                    val record = TaskCodec.decode(c.getString(1))
                    if (record.libraryId == libraryId && record.state is TaskState.Finished) add(c.getString(0))
                } }
            }
            tasks.forEach { db.execSQL("UPDATE read_status_changes SET dismissed = 1 WHERE task_id = ?", arrayOf(it)) }
        }

        /**
         * Called inside the metadata publication transaction, after selection/control validation.
         * A complete import and its task outcome become durable together. Later controls therefore
         * cannot cancel or pause an already published result, including across process death.
         * The coordinator emits invalidation events only after this transaction commits.
         */
        internal fun completePublication(db: SQLiteDatabase, id: UUID) {
            val args = arrayOf(id.toString())
            val record = db.rawQuery("SELECT record, control FROM queued_tasks WHERE task_id = ?", args).use {
                require(it.moveToFirst())
                require(it.isNull(1))
                TaskCodec.decode(it.getString(0))
            }
            require(record.submission.request is TaskRequest.CandidateConfiguration || record.submission.request is TaskRequest.FormatCopy || record.submission.request is TaskRequest.CoverLoad)
            require(record.state is TaskState.Running)
            require(!isRevoked(db, TaskId(id)))
            val completed = record.copy(state = TaskState.Finished(TaskResult.Completed), controls = noControls)
            db.update("queued_tasks", ContentValues().apply {
                put("record", TaskCodec.encode(completed)); put("recovery_required", 0)
                putNull("checkpoint"); putNull("checkpoint_backend"); putNull("checkpoint_version"); putNull("control")
            }, "task_id = ?", args)
        }

        /**
         * Called in the import transaction; checks survive process death before the next dispatch.
         * [checksCopy] decides from the Calibre record stored with each copy and its record in [imported].
         */
        internal fun enqueueDownloadedChecks(db: SQLiteDatabase, libraryId: io.github.chenxiex.calibrecloud.model.LibraryId,
            imported: io.github.chenxiex.calibrecloud.metadata.ParsedLibrary,
            checksCopy: (recorded: CalibreStamp?, imported: CalibreStamp?) -> Boolean) {
            val books = imported.books.associateBy { it.sourceId to it.sourceUuid.toString() }
            db.rawQuery("""SELECT source_id, source_uuid, format, calibre_recorded, calibre_modified, calibre_size
                FROM downloaded_copies WHERE library_id = ? ORDER BY source_id, source_uuid, format""",
                arrayOf(libraryId.value.toString())).use { c ->
                while (c.moveToNext()) {
                    val format = io.github.chenxiex.calibrecloud.model.BookFormat.parse(c.getString(2))
                    val recorded = if (c.getInt(3) == 0) null
                        else CalibreStamp(if (c.isNull(4)) null else c.getString(4), if (c.isNull(5)) null else c.getLong(5))
                    val book = books[c.getLong(0) to c.getString(1)]
                    val current = book?.formats?.find { it.format == format }?.let { CalibreStamp(book.lastModified, it.sizeBytes) }
                    if (!checksCopy(recorded, current)) continue
                    enqueueAutomatic(db, TaskRequest.FormatCheck(io.github.chenxiex.calibrecloud.model.CopyKey(
                        io.github.chenxiex.calibrecloud.model.BookKey(libraryId, c.getLong(0), UUID.fromString(c.getString(1))), format)))
                }
            }
        }

        internal fun enqueueAutomatic(db: SQLiteDatabase, request: TaskRequest) {
            val submission = TaskSubmission(request, TaskOrigin.DOWNLOADED_FORMAT_UPDATE)
            val exists = db.rawQuery("SELECT record FROM queued_tasks", null).use { c ->
                var found = false
                while (c.moveToNext()) {
                    val record = TaskCodec.decode(c.getString(0))
                    if (record.state !is TaskState.Finished && record.submission.key == submission.key) found = true
                }
                found
            }
            if (exists) return
            val next = db.rawQuery("SELECT next_value FROM queue_sequence WHERE singleton = 1", null).use { it.moveToFirst(); it.getLong(0) }
            check(next < Long.MAX_VALUE)
            db.execSQL("UPDATE queue_sequence SET next_value = ? WHERE singleton = 1", arrayOf(next + 1))
            val record = TaskRecord(TaskId(UUID.randomUUID()), submission, SchedulingPosition(TaskPriority.LOW, QueueSequence(next)),
                state = TaskState.Queued, controls = queuedControls)
            db.insertOrThrow("queued_tasks", null, ContentValues().apply {
                put("task_id", record.id.value.toString()); put("record", TaskCodec.encode(record)); put("stage", initialStage(request).code)
            })
        }

        /** Finished tasks the task page keeps (Q74): about ten pages of history. */
        internal const val FINISHED_HISTORY = 50
        internal val queuedControls = TaskControls(false, true, false, false)
        internal val noControls = TaskControls(false, false, false, false)
        fun initialStage(request: TaskRequest): TaskStage = when (request) {
            is TaskRequest.CandidateConfiguration -> TaskStage.CANDIDATE_ACCESS
            is TaskRequest.FormatCopy -> TaskStage.FORMAT_TRANSFER
            is TaskRequest.FormatCheck -> TaskStage.FORMAT_CHECK
            is TaskRequest.CoverLoad -> TaskStage.COVER_TRANSFER
            is TaskRequest.ReadStatusWrite -> TaskStage.WRITE_SNAPSHOT
        }
        /**
         * Requirement code of the edge from a task to the stale-path sync it submitted while running (R11).
         * It is not part of the submission: retry removes it, and any finished result satisfies it.
         */
        internal const val AWAITED_SYNC = "awaited_sync"

        private fun satisfies(record: TaskRecord, requirement: String): Boolean = when (requirement) {
            AWAITED_SYNC, DependencyRequirement.FINISHED.code -> record.state is TaskState.Finished
            DependencyRequirement.SUCCESS.code -> (record.state as? TaskState.Finished)?.result.let { it == TaskResult.Completed || it is TaskResult.CompletedWithBookFailures }
            else -> false
        }
    }
}
