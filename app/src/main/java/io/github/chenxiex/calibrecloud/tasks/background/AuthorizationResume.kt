package io.github.chenxiex.calibrecloud.tasks.background

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorizations
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl

/**
 * Continues work that waited for login or directory authorization; the caller wakes the queue afterwards.
 * Library tasks simply run again on wakeup. Candidate tasks are bound to one selection token and
 * authorization, so their work continues as an equivalent new request and the superseded request is
 * cancelled. A sync continues only while it still belongs to the current selection, or when the
 * same imported library was selected again, as re-authorizing a directory grant does; a directory listing
 * continues only for the library still being added. This never switches the current library.
 * Candidates that can no longer run are cancelled. Cancelling a candidate of the
 * current token would revoke that token, so only superseded tokens are cancelled. Nothing is resubmitted
 * for a backend whose [io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorization.ready] is false.
 */
class AuthorizationResume(
    private val queue: DurableTaskQueue,
    private val state: ApplicationStateRepository,
    private val authorizations: LibraryAuthorizations,
    private val requestSync: suspend (TaskOrigin) -> TaskId?,
    /** Continues a waiting directory listing for the library being added. */
    private val continueConfiguration: suspend (TaskRequest.CandidateConfiguration) -> TaskId?,
) {
    suspend fun resume() {
        val ready = BackendKind.entries.filter { authorizations.of(it).ready() }.toSet()
        val waiting = queue.list().map { it.record }.filter { record ->
            val request = record.submission.request as? TaskRequest.CandidateConfiguration
            val reasons = (record.state as? TaskState.Waiting)?.reasons.orEmpty()
            request != null && request.context.backend in ready &&
                (WaitingReason.LOGIN in reasons || WaitingReason.DIRECTORY_AUTHORIZATION in reasons)
        }
        val current = state.current()
        val addition = state.addition()
        for (record in waiting) {
            val request = record.submission.request as TaskRequest.CandidateConfiguration
            if (request.operation != TaskRequest.CandidateConfiguration.LIBRARY_SYNC) {
                val replacement = if (request.context.selectionToken == addition?.token) continueConfiguration(request) else null
                if (replacement != record.id) queue.control(record.id, TaskControl.CANCEL)
                continue
            }
            val active = queue.isActive(record)
            val sameLibrary = current?.backend == request.context.backend && current.identity != null &&
                queue.scopeLibrary(record.id) == current.identity.id
            if (!active && !sameLibrary) {
                if (request.context.selectionToken != current?.token) queue.control(record.id, TaskControl.CANCEL)
                continue
            }
            val replacement = requestSync(record.effectiveOrigin)
            if (replacement != null && replacement != record.id &&
                request.context.selectionToken != state.current()?.token) queue.control(record.id, TaskControl.CANCEL)
        }
    }
}
