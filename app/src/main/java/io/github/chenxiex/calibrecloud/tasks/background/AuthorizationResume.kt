package io.github.chenxiex.calibrecloud.tasks.background

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl

/**
 * Continues work that waited for login or directory authorization; the caller wakes the queue afterwards.
 * Library tasks simply run again on wakeup. Candidate tasks are bound to one selection token and login
 * session, so their work continues as an equivalent new request and the superseded request is cancelled.
 * A candidate continues only while it still belongs to the current selection, or, after a local
 * re-selection, when the same imported library was chosen again; this never switches the current
 * library. Candidates that can no longer run are cancelled. Cancelling a candidate of the current token
 * would revoke that token, so only superseded tokens are cancelled. Nothing is resubmitted for a
 * backend whose authorization is still missing.
 */
class AuthorizationResume(
    private val queue: DurableTaskQueue,
    private val state: ApplicationStateRepository,
    private val oneDriveReady: suspend () -> Boolean,
    private val localReady: suspend () -> Boolean,
    private val resubmitSync: suspend (TaskOrigin) -> TaskId?,
    private val browse: suspend (String?) -> TaskId?,
) {
    suspend fun resume() {
        val ready = buildSet {
            if (oneDriveReady()) add(BackendKind.ONEDRIVE)
            if (localReady()) add(BackendKind.LOCAL)
        }
        val waiting = queue.list().map { it.record }.filter { record ->
            val request = record.submission.request as? TaskRequest.CandidateConfiguration
            val reasons = (record.state as? TaskState.Waiting)?.reasons.orEmpty()
            request != null && request.context.backend in ready &&
                (WaitingReason.LOGIN in reasons || WaitingReason.DIRECTORY_AUTHORIZATION in reasons)
        }
        val current = state.current()
        for (record in waiting) {
            val request = record.submission.request as TaskRequest.CandidateConfiguration
            val active = queue.isActive(record)
            val sameLocalLibrary = request.context.backend == BackendKind.LOCAL && current?.backend == BackendKind.LOCAL &&
                current.identity != null && queue.scopeLibrary(record.id) == current.identity.id
            if (!active && !sameLocalLibrary) {
                if (request.context.selectionToken != current?.token) queue.control(record.id, TaskControl.CANCEL)
                continue
            }
            val replacement = if (request.operation == OneDriveCandidateTaskHandler.BROWSE) browse(request.directoryItemId)
                else resubmitSync(record.effectiveOrigin)
            if (replacement != null && replacement != record.id &&
                request.context.selectionToken != state.current()?.token) queue.control(record.id, TaskControl.CANCEL)
        }
    }
}
