package io.github.chenxiex.calibrecloud.tasks.background

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.CandidateContext
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskSubmission
import io.github.chenxiex.calibrecloud.tasks.local.LocalSnapshotTaskHandler
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Process-owned R19 startup gate. Construction and worker recovery never submit a sync.
 * Only the user-facing main Activity calls [onMainOpened]; repeated Activity instances share this gate.
 * The first main opening consumes the opportunity even if the setting is disabled or no root is selected.
 * Submission uses the same candidate request as manual sync, so the durable queue can reuse and promote it.
 */
class StartupSync(
    private val state: ApplicationStateRepository,
    private val coordinator: TaskCoordinator,
    private val sessionId: suspend () -> UUID? = { null },
) {
    private val openingLock = Mutex()
    private var mainOpened = false

    suspend fun startupEnabled(): Boolean = state.startupEnabled()

    suspend fun setStartupEnabled(enabled: Boolean) = state.setStartupEnabled(enabled)

    /** Enqueues without waiting for network or source processing; background execution owns draining. */
    suspend fun onMainOpened(): TaskId? = openingLock.withLock {
        if (mainOpened) return@withLock null
        mainOpened = true
        if (!startupEnabled()) return@withLock null
        submit(TaskOrigin.STARTUP_SYNC)
    }

    suspend fun manualSync(): Boolean = submit(TaskOrigin.MANUAL_SYNC) != null

    private suspend fun submit(origin: TaskOrigin): TaskId? {
        val selected = state.current() ?: return null
        if (selected.location == null) return null
        val context: CandidateContext
        val operation: String
        when (selected.backend) {
            BackendKind.LOCAL -> {
                context = CandidateContext(selected.token, selected.backend, selected.authorizationId ?: selected.token)
                operation = LocalSnapshotTaskHandler.OPERATION
            }
            BackendKind.ONEDRIVE -> {
                val session = sessionId() ?: selected.authorizationId ?: return null
                context = if (selected.authorizationId == session) {
                    CandidateContext(selected.token, selected.backend, session)
                } else {
                    state.reauthorizeCandidate(selected.token, session) ?: return null
                }
                operation = OneDriveCandidateTaskHandler.SNAPSHOT
            }
        }
        return when (val result = coordinator.submit(TaskSubmission(
            TaskRequest.CandidateConfiguration(context, operation), origin,
        ))) {
            is SubmissionResult.Created -> result.taskId
            is SubmissionResult.Reused -> result.taskId
            is SubmissionResult.Promoted -> result.taskId
            is SubmissionResult.Rejected -> null
        }
    }
}
