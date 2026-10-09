package io.github.chenxiex.calibrecloud.tasks.persistence

import io.github.chenxiex.calibrecloud.storage.api.LibrarySource
import io.github.chenxiex.calibrecloud.storage.api.Reauthorization
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface StageOutcome {
    data class Advance(val stage: TaskStage) : StageOutcome
    /** The handler has atomically published a complete cache; no half-file events are permitted. */
    data class Complete(val result: TaskResult = TaskResult.Completed, val cachePublished: Boolean = false) : StageOutcome
    data class Fail(val error: TaskError) : StageOutcome
    data class Wait(val reason: WaitingReason) : StageOutcome
    /** Only transient transport failure or server throttling; permanent errors use Fail. */
    data class Retry(val error: TaskError, val serverDelayMillis: Long? = null) : StageOutcome {
        init { require(serverDelayMillis == null || serverDelayMillis >= 0) }
    }
    /**
     * The source path was not found; [sync] refreshes metadata first (R11). The task waits on it, then
     * runs again once with the new import. Only a task without a recorded sync may return this.
     */
    data class AwaitSync(val sync: TaskId, val missingPath: io.github.chenxiex.calibrecloud.model.RelativeSourcePath) : StageOutcome
}

data class RecoveryDecision(val stage: TaskStage, val checkpoint: RecoveryCheckpoint?)

/**
 * Recoverable authorization loss waits until re-login or directory re-authorization wakes the queue.
 * Other authorization failures, such as a server's permanent denial, remain failures.
 */
fun authorizationWait(source: LibrarySource, kind: StorageErrorKind): WaitingReason? = when (source.reauthorization(kind)) {
    Reauthorization.SIGN_IN -> WaitingReason.LOGIN
    Reauthorization.DIRECTORY_GRANT -> WaitingReason.DIRECTORY_AUTHORIZATION
    null -> null
}

/** After its stale-path sync, a task whose newly imported path is still the missing one needs no request. */
fun QueueEntry.knownMissing(path: io.github.chenxiex.calibrecloud.model.RelativeSourcePath) = sourceSync != null && missingPath == path

sealed interface StaleSource {
    data class Await(val outcome: StageOutcome.AwaitSync) : StaleSource
    /** The path was absent after a completed sync, or no sync applies: the source file is missing. */
    data object Confirmed : StaleSource
    /** The sync did not complete, so absence is not established; the task fails without marking copies. */
    data object Unconfirmed : StaleSource
}

/**
 * R11 for a source path that was not found. When [source] resyncs missing paths, the task first submits
 * one metadata sync through [requestSync], inheriting its effective origin and priority, and waits for
 * it; after that sync it is missing only if the sync completed. No loop and no directory search.
 */
suspend fun staleSource(entry: QueueEntry, source: LibrarySource, path: io.github.chenxiex.calibrecloud.model.RelativeSourcePath,
    queue: DurableTaskQueue, requestSync: suspend (TaskOrigin) -> TaskId?): StaleSource {
    if (!source.resyncsMissingPath) return StaleSource.Confirmed
    val sync = entry.sourceSync ?: return requestSync(entry.record.effectiveOrigin)
        ?.let { StaleSource.Await(StageOutcome.AwaitSync(it, path)) } ?: StaleSource.Confirmed
    return if (queue.get(sync)?.record?.state == TaskState.Finished(TaskResult.Completed)) StaleSource.Confirmed
        else StaleSource.Unconfirmed
}

/**
 * Handler registration is internal to the dependency container; only implemented source operations are registered.
 * recovery MUST inspect latest source/version and staging evidence before approving continuation.
 * A stage cooperatively checks execution.checkControl() while working, closes resources on exit,
 * and never publishes after cancellation. Source commit has neither pause nor cancel capability.
 */
interface TaskHandler {
    fun supports(request: TaskRequest): Boolean
    fun controls(stage: TaskStage): TaskControls
    suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision
    suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome
    /** Serial safe-boundary cleanup; never deletes published cache or source data. */
    suspend fun stopped(entry: QueueEntry) {}
}

private class BoundaryControl(val command: TaskControl) : RuntimeException()
private class YieldBoundary : RuntimeException()

class TaskExecution internal constructor(private val queue: DurableTaskQueue, val id: TaskId) {
    /** Set by the coordinator while a pausable stage runs: its safe boundaries also yield to user requests. */
    internal var yieldable = false

    /**
     * A safe boundary: applies a pending pause or cancel and, in a pausable stage, yields to a waiting
     * high-priority request (R17). Either ends the stage by throwing; the handler only closes resources.
     */
    suspend fun checkControl() {
        val command = queue.boundaryControl(id)
        if (command == TaskControl.PAUSE || command == TaskControl.CANCEL) throw BoundaryControl(command)
        if (yieldable && queue.highPriorityWaiting(id)) throw YieldBoundary()
    }

    /** Ends this task as cancelled at the handler's own safe boundary, as a user cancel would. */
    fun cancelHere(): Nothing = throw BoundaryControl(TaskControl.CANCEL)

    /** Records progress of the running stage without changing its checkpoint. */
    suspend fun progress(progress: TaskProgress) {
        checkControl()
        queue.update(id) { entry -> entry.copy(record = entry.record.copy(state = TaskState.Running(entry.stage, progress))) }
    }

    suspend fun checkpoint(value: RecoveryCheckpoint, progress: TaskProgress? = null) {
        checkControl()
        queue.update(id) { entry ->
            entry.copy(checkpoint = value, record = entry.record.copy(state = TaskState.Running(entry.stage, progress)))
        }
    }

    /** Record actual fallback to a full transfer, never inferred by the UI from a zero offset. */
    suspend fun markTransferRestart() {
        checkControl()
        queue.update(id) { entry -> entry.copy(record = entry.record.copy(restartedTransfer = true)) }
    }

    /** Persist immediately after source outcome is established, before refetch or any next selection. */
    suspend fun recordCommit(commit: CommitState) {
        queue.update(id) { entry ->
            require(entry.record.submission.request is TaskRequest.ReadStatusWrite)
            require(entry.stage == TaskStage.WRITE_COMMIT || entry.stage == TaskStage.RECOVERY_CHECK)
            require(entry.record.commit != CommitState.Confirmed || commit == CommitState.Confirmed)
            entry.copy(record = entry.record.copy(commit = commit))
        }
    }
}

/**
 * All foreground/background wakeups share queue.executionLock. A full resource workflow retains
 * execution until complete/pause/wait/failure; the next resource is selected afresh. No preemption.
 * Unknown or confirmed-but-unrefreshed writes reserve dispatch, including across process recovery.
 * WorkManager wakes this same coordinator; its scheduling does not determine resource order.
 */
class TaskCoordinator(
    private val queue: DurableTaskQueue,
    private val handlers: List<TaskHandler>,
    private val conditions: (TaskRecord) -> Set<WaitingReason> = { emptySet() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun submit(submission: TaskSubmission): SubmissionResult {
        if (handlers.count { it.supports(submission.request) } != 1) return SubmissionResult.Rejected(
            TaskError.Source(StorageError(StorageErrorKind.UNSUPPORTED_OPERATION)))
        return queue.submit(submission)
    }

    /** Private-state recovery only. Never waits for an active executor or touches source storage. */
    suspend fun restorePending() {
        if (!queue.executionLock.tryLock()) return
        try { queue.beforeDispatch(); queue.recover() } finally { queue.executionLock.unlock() }
    }

    /** UI requests persistent platform execution rather than owning a lifecycle-bound drain. */
    suspend fun requestRun() = queue.onWake()

    /** Shared foreground/test/background driver; returns when no currently executable work remains. */
    suspend fun drain() = queue.executionLock.withLock {
        queue.beforeDispatch()
        queue.list().filter { it.record.state is TaskState.Finished &&
            (it.record.state as TaskState.Finished).result is TaskResult.Cancelled && it.checkpoint != null }.forEach { entry ->
            handlers.singleOrNull { it.supports(entry.record.submission.request) }?.stopped(entry)
            queue.update(entry.record.id) { it.copy(checkpoint = null) }
        }
        queue.recover()
        val deferred = mutableSetOf<TaskId>()
        while (true) {
            val entry = queue.claim(now(), conditions, { request -> handlers.count { it.supports(request) } == 1 }, excluded = deferred) ?: break
            // A handler Wait is retried by a subsequent explicit wakeup, not busy-looped here. A task
            // awaiting its sync stays claimable: the queue holds it until that sync finishes.
            if (!execute(entry)) deferred.add(entry.record.id)
        }
    }

    /**
     * Returns true when the task may run again in this drain: it waits for its own stale-path sync, or it
     * yielded to a user request and continues after it. Other outcomes are not claimed again until the next wakeup.
     */
    private suspend fun execute(initial: QueueEntry): Boolean {
        val handler = handlers.single { it.supports(initial.record.submission.request) }
        val execution = TaskExecution(queue, initial.record.id)
        try {
            var entry = initial
            if (entry.recoveryRequired) {
                val decision = handler.recover(entry, execution)
                validateRecovery(entry, decision)
                entry = queue.update(entry.record.id) {
                    it.copy(stage = decision.stage, checkpoint = decision.checkpoint, recoveryRequired = false,
                        record = it.record.copy(state = TaskState.Running(decision.stage)))
                }
            }
            while (true) {
                if (!queue.isActive(entry.record)) {
                    queue.update(entry.record.id) { current -> current.copy(recoveryRequired = true,
                        record = current.record.copy(state = if (current.record.submission.request is TaskRequest.CandidateConfiguration)
                            TaskState.Finished(TaskResult.Cancelled(current.record.commit))
                            else TaskState.Waiting(FrozenSet(listOf(WaitingReason.INACTIVE_LIBRARY))), controls = DurableTaskQueue.queuedControls)) }
                    return false
                }
                val stage = entry.stage
                val controls = handler.controls(stage)
                require(!controls.canResume && !controls.canRetry)
                require(stage != TaskStage.WRITE_COMMIT || (!controls.canPause && !controls.canCancel))
                execution.checkControl()
                entry = queue.update(entry.record.id) {
                    it.copy(record = it.record.copy(state = TaskState.Running(stage), controls = controls,
                        commit = if (stage == TaskStage.WRITE_COMMIT && it.record.commit == CommitState.NotCommitted)
                            CommitState.Unknown(UUID.randomUUID()) else it.record.commit))
                }
                execution.checkControl()
                execution.yieldable = controls.canPause
                val outcome = try { handler.execute(entry, execution) } finally { execution.yieldable = false }
                // Import publication and terminal state may already be committed atomically.
                // Switching selections after that point does not cancel the completed old-library task.
                if (outcome is StageOutcome.Complete && outcome.cachePublished &&
                    queue.get(entry.record.id)?.record?.state == TaskState.Finished(TaskResult.Completed)) {
                    queue.update(entry.record.id, cachePublished = true) { it }
                    return false
                }
                execution.checkControl()
                if (entry.record.submission.request is TaskRequest.CandidateConfiguration && !queue.isActive(entry.record)) {
                    queue.update(entry.record.id) { current -> current.copy(record = current.record.copy(
                        state = TaskState.Finished(TaskResult.Cancelled(current.record.commit)), controls = DurableTaskQueue.noControls)) }
                    return false
                }
                when (outcome) {
                    is StageOutcome.Advance -> {
                        val current = requireNotNull(queue.get(entry.record.id))
                        validateAdvance(current, outcome.stage)
                        entry = queue.update(entry.record.id) {
                            it.copy(stage = outcome.stage, attempts = 0, retryAt = 0,
                                record = it.record.copy(state = TaskState.Running(outcome.stage), controls = DurableTaskQueue.noControls))
                        }
                    }
                    is StageOutcome.Complete -> {
                        require(outcome.result == TaskResult.Completed || outcome.result is TaskResult.CompletedWithBookFailures)
                        require(isFinalStage(entry.record.submission.request, stage))
                        // Candidate import events require the atomically completed publication path above.
                        require(!outcome.cachePublished || entry.record.submission.request !is TaskRequest.CandidateConfiguration)
                        queue.update(entry.record.id, cachePublished = outcome.cachePublished) { current ->
                            current.copy(record = current.record.copy(state = TaskState.Finished(outcome.result), controls = DurableTaskQueue.noControls),
                                checkpoint = null, control = null)
                        }
                        return false
                    }
                    is StageOutcome.Fail -> { fail(entry.record.id, outcome.error); return false }
                    is StageOutcome.AwaitSync -> {
                        require(entry.sourceSync == null && outcome.sync != entry.record.id)
                        queue.update(entry.record.id) { it.copy(sourceSync = outcome.sync, missingPath = outcome.missingPath, recoveryRequired = true,
                            record = it.record.copy(state = TaskState.Waiting(FrozenSet(listOf(WaitingReason.DEPENDENCY))),
                                controls = DurableTaskQueue.queuedControls)) }
                        return true
                    }
                    is StageOutcome.Wait -> {
                        queue.update(entry.record.id) { it.copy(record = it.record.copy(state = TaskState.Waiting(FrozenSet(listOf(outcome.reason))),
                            controls = DurableTaskQueue.queuedControls), recoveryRequired = true) }
                        return false
                    }
                    is StageOutcome.Retry -> {
                        val kind = (outcome.error as? TaskError.Source)?.error?.kind
                        require(kind in setOf(StorageErrorKind.NO_NETWORK, StorageErrorKind.THROTTLED, StorageErrorKind.LOCAL_IO))
                        val reason = if (kind == StorageErrorKind.THROTTLED) WaitingReason.THROTTLED else WaitingReason.NETWORK
                        val latest = requireNotNull(queue.get(entry.record.id))
                        if (latest.attempts >= MAX_RETRIES) { fail(entry.record.id, outcome.error); return false }
                        val delay = maxOf(outcome.serverDelayMillis ?: 0, 1_000L shl latest.attempts)
                        val deadline = now().let { if (it > Long.MAX_VALUE - delay) Long.MAX_VALUE else it + delay }
                        if (kind == StorageErrorKind.THROTTLED) queue.throttle(entry.record.id, outcome.serverDelayMillis
                            ?.let { server -> now().let { if (it > Long.MAX_VALUE - server) Long.MAX_VALUE else it + server } } ?: deadline)
                        queue.update(entry.record.id) { it.copy(attempts = it.attempts + 1, retryAt = deadline, recoveryRequired = true,
                            record = it.record.copy(state = TaskState.Waiting(FrozenSet(listOf(reason))), controls = DurableTaskQueue.queuedControls)) }
                        return false
                    }
                }
            }
        } catch (_: YieldBoundary) {
            // Back in line at its own position, like a pause that resumes by itself; staging is kept and
            // recovery checks it before the task continues where it stopped.
            queue.update(initial.record.id) { entry ->
                entry.copy(recoveryRequired = true, record = entry.record.copy(state = TaskState.Queued, controls = DurableTaskQueue.queuedControls))
            }
            return true
        } catch (control: BoundaryControl) {
            handler.stopped(requireNotNull(queue.get(initial.record.id)))
            queue.update(initial.record.id) { entry ->
                val state = if (control.command == TaskControl.PAUSE) TaskState.Paused(entry.stage)
                    else TaskState.Finished(TaskResult.Cancelled(entry.record.commit))
                entry.copy(control = null, recoveryRequired = true, record = entry.record.copy(state = state,
                    controls = if (control.command == TaskControl.PAUSE) TaskControls(false, true, true, false)
                        else TaskControls(false, false, false, entry.record.commit != CommitState.NotCommitted)))
            }
        } catch (cancelled: CancellationException) {
            // Running/checkpoint evidence remains durable for the next coordinator's recovery check.
            throw cancelled
        } catch (_: Exception) {
            fail(initial.record.id, TaskError.Source(StorageError(StorageErrorKind.LOCAL_IO)))
        }
        return false
    }

    private suspend fun fail(id: TaskId, error: TaskError) = queue.update(id) { entry ->
        entry.copy(record = entry.record.copy(state = TaskState.Finished(TaskResult.Failed(StageFailure(entry.stage, error, entry.record.commit))),
            controls = TaskControls(false, false, false, true)), recoveryRequired = true, control = null)
    }

    private fun validateRecovery(entry: QueueEntry, decision: RecoveryDecision) {
        val request = entry.record.submission.request
        require(decision.stage in stages(request))
        when (entry.record.commit) {
            CommitState.Confirmed -> require(decision.stage == TaskStage.WRITE_REFETCH || decision.stage == TaskStage.WRITE_IMPORT)
            is CommitState.Unknown -> require(decision.stage == TaskStage.RECOVERY_CHECK)
            CommitState.NotCommitted -> require(decision.stage != TaskStage.RECOVERY_CHECK)
        }
        if (decision.checkpoint != null) require(decision.checkpoint.version != null)
    }

    private fun validateAdvance(entry: QueueEntry, next: TaskStage) {
        val stages = stages(entry.record.submission.request)
        if (entry.stage == TaskStage.RECOVERY_CHECK) {
            require(next == if (entry.record.commit == CommitState.Confirmed) TaskStage.WRITE_REFETCH else TaskStage.WRITE_SNAPSHOT)
            require(entry.record.commit !is CommitState.Unknown)
        } else {
            require(stages.indexOf(next) == stages.indexOf(entry.stage) + 1 && next != TaskStage.RECOVERY_CHECK)
            if (entry.stage == TaskStage.WRITE_COMMIT) require(entry.record.commit == CommitState.Confirmed)
        }
    }
    /** A cover batch publishes each cover within its transfer stage; COVER_PUBLISH remains for tasks persisted before batches. */
    private fun isFinalStage(request: TaskRequest, stage: TaskStage) = stage == stages(request).last { it != TaskStage.RECOVERY_CHECK } ||
        (request is TaskRequest.CoverLoad && stage == TaskStage.COVER_TRANSFER)
    private fun stages(request: TaskRequest): List<TaskStage> = when (request) {
        is TaskRequest.CandidateConfiguration -> listOf(TaskStage.CANDIDATE_ACCESS)
        is TaskRequest.MetadataSync -> listOf(TaskStage.METADATA_FETCH, TaskStage.METADATA_IMPORT)
        is TaskRequest.FormatCopy -> listOf(TaskStage.FORMAT_TRANSFER, TaskStage.FORMAT_PUBLISH)
        is TaskRequest.FormatCheck -> listOf(TaskStage.FORMAT_CHECK)
        is TaskRequest.CoverLoad -> listOf(TaskStage.COVER_TRANSFER, TaskStage.COVER_PUBLISH)
        is TaskRequest.ReadStatusWrite -> listOf(TaskStage.WRITE_SNAPSHOT, TaskStage.WRITE_PREPARE, TaskStage.WRITE_COMMIT,
            TaskStage.WRITE_REFETCH, TaskStage.WRITE_IMPORT, TaskStage.RECOVERY_CHECK)
    }
    companion object { private const val MAX_RETRIES = 3 }
}
