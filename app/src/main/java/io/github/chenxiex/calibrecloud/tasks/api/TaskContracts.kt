package io.github.chenxiex.calibrecloud.tasks.api

import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Allocated transactionally by the future persistent queue, not by wall-clock time. */
data class QueueSequence(val value: Long) {
    init { require(value >= 0) }
}

data class SchedulingPosition(val priority: TaskPriority, val sequence: QueueSequence)

/** Apply only after eligibility/dependency checks; does not select or preempt a running task. */
fun compareSchedulingPositions(left: SchedulingPosition, right: SchedulingPosition): Int {
    if (left.priority != right.priority) return if (left.priority == TaskPriority.HIGH) -1 else 1
    return left.sequence.value.compareTo(right.sequence.value)
}

/** The durable queue allocates a NEW high sequence at this user request, including prerequisites. */
data class PriorityPromotion(val origin: TaskOrigin, val sequence: QueueSequence) {
    init { require(origin.priority == TaskPriority.HIGH) }
}

enum class TaskStage(val code: String) {
    METADATA_FETCH("metadata_fetch"), METADATA_IMPORT("metadata_import"),
    FORMAT_TRANSFER("format_transfer"), FORMAT_PUBLISH("format_publish"),
    COVER_TRANSFER("cover_transfer"), COVER_PUBLISH("cover_publish"),
    WRITE_SNAPSHOT("write_snapshot"), WRITE_PREPARE("write_prepare"), WRITE_COMMIT("write_commit"),
    WRITE_REFETCH("write_refetch"), WRITE_IMPORT("write_import"), RECOVERY_CHECK("recovery_check"),
}

enum class WaitingReason(val code: String) {
    NETWORK("network"), LOGIN("login"), DIRECTORY_AUTHORIZATION("directory_authorization"),
    DEPENDENCY("dependency"), CURRENT_TASK("current_task"), HIGHER_PRIORITY("higher_priority"),
    INACTIVE_LIBRARY("inactive_library"), RECOVERY("recovery"),
}

/** Total null means unknown: UI renders text, never an animated indeterminate indicator. */
data class TaskProgress(val completed: Long, val total: Long? = null) {
    init { require(completed >= 0 && (total == null || total >= completed)) }
}

/** Protection record is retained across ordinary cache cleanup. Unknown is never directly replayable. */
sealed interface CommitState {
    data object NotCommitted : CommitState
    data object Confirmed : CommitState
    data class Unknown(val recoveryRecordId: UUID) : CommitState
}

enum class RetryFrom(val code: String) {
    FAILED_STAGE("failed_stage"), WRITE_REFETCH("write_refetch"), RECOVERY_CHECK("recovery_check"),
}

sealed interface TaskError {
    data class Source(val error: StorageError) : TaskError
    data object InvalidColumn : TaskError
    data class BookIdentityChanged(val book: BookKey) : TaskError
}

data class StageFailure(val stage: TaskStage, val error: TaskError, val commit: CommitState) {
    val retryFrom: RetryFrom get() = when (commit) {
        CommitState.NotCommitted -> RetryFrom.FAILED_STAGE
        CommitState.Confirmed -> RetryFrom.WRITE_REFETCH
        is CommitState.Unknown -> RetryFrom.RECOVERY_CHECK
    }
}

data class BookFailure(val book: BookKey, val error: TaskError)

sealed interface TaskResult {
    /** For write workflows, completion means source commit AND successful re-import. */
    data object Completed : TaskResult
    /** Valid targets were committed and re-imported; invalid entries remain individually visible. */
    data class CompletedWithBookFailures(val failures: FrozenSet<BookFailure>) : TaskResult {
        init { require(failures.isNotEmpty()) }
    }
    data class Failed(val failure: StageFailure) : TaskResult
    /** Cancelling confirmed writes never undoes source changes; refetch/recovery remains necessary. */
    data class Cancelled(val commit: CommitState) : TaskResult
}

sealed interface TaskState {
    data object Queued : TaskState
    data class Waiting(val reasons: FrozenSet<WaitingReason>) : TaskState {
        init { require(reasons.isNotEmpty()) }
    }
    data class Running(val stage: TaskStage, val progress: TaskProgress? = null) : TaskState
    data class Paused(val stage: TaskStage) : TaskState
    data class Finished(val result: TaskResult) : TaskState
}

/** Capabilities are supplied by the actual handler at its current safe boundary, not a UI promise. */
data class TaskControls(val canPause: Boolean, val canCancel: Boolean, val canResume: Boolean, val canRetry: Boolean)

data class TaskRecord(
    val id: TaskId,
    val submission: TaskSubmission,
    val scheduling: SchedulingPosition,
    val promotion: PriorityPromotion? = null,
    val state: TaskState,
    val controls: TaskControls,
    val commit: CommitState = CommitState.NotCommitted,
) {
    val libraryId get() = submission.request.libraryId
    val originalOrigin get() = submission.origin
    val effectiveOrigin get() = promotion?.origin ?: originalOrigin

    init {
        require(state !is TaskState.Running || state.stage != TaskStage.WRITE_COMMIT ||
            (!controls.canPause && !controls.canCancel))
        val result = (state as? TaskState.Finished)?.result
        require(result !is TaskResult.Failed || result.failure.commit == commit)
        require(result !is TaskResult.Cancelled || result.commit == commit)
        val completed = result == TaskResult.Completed || result is TaskResult.CompletedWithBookFailures
        require(!completed || commit !is CommitState.Unknown)
        if (result is TaskResult.CompletedWithBookFailures) {
            val write = submission.request as? TaskRequest.ReadStatusWrite
            require(write != null && result.failures.all { it.book in write.books })
        }
        require(!completed || submission.request !is TaskRequest.ReadStatusWrite ||
            commit == CommitState.Confirmed)
        require(submission.dependencies.none { it.taskId == id })
        require(scheduling.priority == effectiveOrigin.priority)
        require(promotion == null || (originalOrigin.priority == TaskPriority.LOW && scheduling.sequence == promotion.sequence))
    }
}

sealed interface SubmissionResult {
    data class Created(val taskId: TaskId) : SubmissionResult
    data class Reused(val taskId: TaskId) : SubmissionResult
    data class Promoted(val taskId: TaskId, val promotion: PriorityPromotion) : SubmissionResult
    data class Rejected(val error: TaskError) : SubmissionResult
}

sealed interface TaskEvent {
    data class Changed(val record: TaskRecord) : TaskEvent
    /** Published only after valid import/copy publication; consumers reread their local data. */
    data class CacheChanged(val taskId: TaskId, val request: TaskRequest) : TaskEvent
}

/**
 * Explicit durable submission and observation only. No implementation exists in phase one.
 * Implementations must atomically persist requests, deduplication, dependencies and queue sequences;
 * only eligible tasks in the active library execute, one at a time without automatic preemption.
 * Waiting work cannot block unrelated eligible work; batches reselect between resource children.
 * Related writes preserve submission order and reserve write-refetch before unrelated selection.
 * An unknown commit blocks dependent writes until recovery establishes a safe boundary.
 */
interface TaskQueue {
    suspend fun submit(submission: TaskSubmission): SubmissionResult
    fun observe(taskId: TaskId): Flow<TaskRecord>
    val events: Flow<TaskEvent>
}
