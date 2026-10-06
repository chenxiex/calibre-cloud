package io.github.chenxiex.calibrecloud.tasks.persistence

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
}

data class RecoveryDecision(val stage: TaskStage, val checkpoint: RecoveryCheckpoint?)

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
}

private class BoundaryControl(val command: TaskControl) : RuntimeException()

class TaskExecution internal constructor(private val queue: DurableTaskQueue, val id: TaskId) {
    suspend fun checkControl() {
        val command = queue.get(id)?.control
        if (command == TaskControl.PAUSE || command == TaskControl.CANCEL) throw BoundaryControl(command)
    }

    suspend fun checkpoint(value: RecoveryCheckpoint, progress: TaskProgress? = null) {
        checkControl()
        queue.update(id) { entry ->
            entry.copy(checkpoint = value, record = entry.record.copy(state = TaskState.Running(entry.stage, progress)))
        }
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
 * WorkManager integration and UI wakeup hooks are deliberately deferred to step 09.
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

    /** Explicit foreground driver; returns when no currently executable work remains. */
    suspend fun drain() = queue.executionLock.withLock {
        queue.recover()
        val deferred = mutableSetOf<TaskId>()
        while (true) {
            val entry = queue.claim(now(), conditions, { request -> handlers.count { it.supports(request) } == 1 }, excluded = deferred) ?: break
            execute(entry)
            // A handler Wait is retried by a subsequent explicit wakeup, not busy-looped here.
            deferred.add(entry.record.id)
        }
    }

    private suspend fun execute(initial: QueueEntry) {
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
                    return
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
                val outcome = handler.execute(entry, execution)
                execution.checkControl()
                if (entry.record.submission.request is TaskRequest.CandidateConfiguration && !queue.isActive(entry.record)) {
                    queue.update(entry.record.id) { current -> current.copy(record = current.record.copy(
                        state = TaskState.Finished(TaskResult.Cancelled(current.record.commit)), controls = DurableTaskQueue.noControls)) }
                    return
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
                        require(!outcome.cachePublished || entry.record.submission.request !is TaskRequest.CandidateConfiguration)
                        queue.update(entry.record.id, cachePublished = outcome.cachePublished) { current ->
                            current.copy(record = current.record.copy(state = TaskState.Finished(outcome.result), controls = DurableTaskQueue.noControls),
                                checkpoint = null, control = null)
                        }
                        return
                    }
                    is StageOutcome.Fail -> { fail(entry.record.id, outcome.error); return }
                    is StageOutcome.Wait -> {
                        queue.update(entry.record.id) { it.copy(record = it.record.copy(state = TaskState.Waiting(FrozenSet(listOf(outcome.reason))),
                            controls = DurableTaskQueue.queuedControls), recoveryRequired = true) }
                        return
                    }
                    is StageOutcome.Retry -> {
                        require((outcome.error as? TaskError.Source)?.error?.kind in setOf(StorageErrorKind.NO_NETWORK, StorageErrorKind.LOCAL_IO))
                        val latest = requireNotNull(queue.get(entry.record.id))
                        if (latest.attempts >= MAX_RETRIES) { fail(entry.record.id, outcome.error); return }
                        val delay = maxOf(outcome.serverDelayMillis ?: 0, 1_000L shl latest.attempts)
                        val deadline = now().let { if (it > Long.MAX_VALUE - delay) Long.MAX_VALUE else it + delay }
                        queue.update(entry.record.id) { it.copy(attempts = it.attempts + 1, retryAt = deadline, recoveryRequired = true,
                            record = it.record.copy(state = TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), controls = DurableTaskQueue.queuedControls)) }
                        return
                    }
                }
            }
        } catch (control: BoundaryControl) {
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
    private fun isFinalStage(request: TaskRequest, stage: TaskStage) = stage == stages(request).last { it != TaskStage.RECOVERY_CHECK }
    private fun stages(request: TaskRequest): List<TaskStage> = when (request) {
        is TaskRequest.CandidateConfiguration -> listOf(TaskStage.CANDIDATE_ACCESS)
        is TaskRequest.MetadataSync -> listOf(TaskStage.METADATA_FETCH, TaskStage.METADATA_IMPORT)
        is TaskRequest.FormatCopy -> listOf(TaskStage.FORMAT_TRANSFER, TaskStage.FORMAT_PUBLISH)
        is TaskRequest.CoverLoad -> listOf(TaskStage.COVER_TRANSFER, TaskStage.COVER_PUBLISH)
        is TaskRequest.ReadStatusWrite -> listOf(TaskStage.WRITE_SNAPSHOT, TaskStage.WRITE_PREPARE, TaskStage.WRITE_COMMIT,
            TaskStage.WRITE_REFETCH, TaskStage.WRITE_IMPORT, TaskStage.RECOVERY_CHECK)
    }
    companion object { private const val MAX_RETRIES = 3 }
}
