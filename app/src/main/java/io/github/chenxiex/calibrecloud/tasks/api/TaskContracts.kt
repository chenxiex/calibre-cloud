package io.github.chenxiex.calibrecloud.tasks.api

import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import kotlinx.coroutines.flow.Flow

/** Allocated transactionally by the persistent queue, not by wall-clock time. */
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
    CANDIDATE_ACCESS("candidate_access"),
    FORMAT_CHECK("format_check"),
    FORMAT_TRANSFER("format_transfer"), FORMAT_PUBLISH("format_publish"),
    COVER_TRANSFER("cover_transfer"), COVER_PUBLISH("cover_publish"),
    WRITE_SNAPSHOT("write_snapshot"), WRITE_PREPARE("write_prepare"), WRITE_COMMIT("write_commit"),
}

enum class WaitingReason(val code: String) {
    NETWORK("network"), THROTTLED("throttled"), LOGIN("login"), DIRECTORY_AUTHORIZATION("directory_authorization"),
    DEPENDENCY("dependency"), INACTIVE_LIBRARY("inactive_library"),
}

/** Total null means unknown: UI renders text, never an animated indeterminate indicator. */
data class TaskProgress(val completed: Long, val total: Long? = null) {
    init { require(completed >= 0 && (total == null || total >= completed)) }
}

sealed interface TaskError {
    data class Source(val error: StorageError) : TaskError
    data object InvalidColumn : TaskError
    data class BookIdentityChanged(val book: BookKey) : TaskError
}

/** A retry starts again from the handler's recovery check, never by replaying a stale stage result. */
data class StageFailure(val stage: TaskStage, val error: TaskError)

data class BookFailure(val book: BookKey, val error: TaskError)

/**
 * A book's read status target still being written (R13, R27), derived from the queue's change lists.
 * It is not a read status: pages show it in place of the read mark and count it for the mark choice
 * (R26), while filtering, search and sorting keep using the import.
 */
sealed interface PendingRead {
    val target: Boolean
    /** In an unfinished write, or pushed and waiting for the sync that follows it to end. */
    data class Pending(override val target: Boolean) : PendingRead
    /** The write failed with [error], was cancelled ([error] null), or this book no longer matched. */
    data class Failed(override val target: Boolean, val error: TaskError?) : PendingRead
}

sealed interface TaskResult {
    /** A read status write completes once its push succeeded (R16); the sync that follows is its own task. */
    data object Completed : TaskResult
    /**
     * A write: the valid changes were pushed; books that no longer match remain individually visible.
     * A cover batch: the other covers were loaded; these books keep their placeholder.
     */
    data class CompletedWithBookFailures(val failures: FrozenSet<BookFailure>) : TaskResult {
        init { require(failures.isNotEmpty()) }
    }
    data class Failed(val failure: StageFailure) : TaskResult
    /** The source push stage cannot be cancelled, so a cancelled write never pushed anything. */
    data object Cancelled : TaskResult
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
    /** A previously retained prefix could not be reused; survives process and stage changes. */
    val restartedTransfer: Boolean = false,
) {
    val libraryId get() = submission.request.libraryId
    val originalOrigin get() = submission.origin
    val effectiveOrigin get() = promotion?.origin ?: originalOrigin

    init {
        require(state !is TaskState.Running || state.stage != TaskStage.WRITE_COMMIT ||
            (!controls.canPause && !controls.canCancel))
        val result = (state as? TaskState.Finished)?.result
        if (result is TaskResult.CompletedWithBookFailures) {
            // A write's books are its change list, kept by the queue beside the record.
            when (val request = submission.request) {
                is TaskRequest.CoverLoad -> require(result.failures.all { it.book in request.books })
                is TaskRequest.ReadStatusWrite -> require(result.failures.all { it.book.libraryId == request.libraryId })
                else -> throw IllegalArgumentException("No book failures for this request")
            }
        }
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
 * Explicit durable submission and observation, implemented by DurableTaskQueue.
 * Implementations must atomically persist requests, deduplication, dependencies and queue sequences;
 * only eligible tasks in the active library execute, one at a time without automatic preemption.
 * Waiting work cannot block unrelated eligible work; batches reselect between resource children.
 * Read status writes of a library run in submission order, each followed directly by its sync (R16).
 */
interface TaskQueue {
    suspend fun submit(submission: TaskSubmission): SubmissionResult
    fun observe(taskId: TaskId): Flow<TaskRecord>
    val events: Flow<TaskEvent>
}
