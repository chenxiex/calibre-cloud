package io.github.chenxiex.calibrecloud.tasks.background

import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorizations
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskSubmission
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-owned R19 startup gate. Construction and worker recovery never submit a sync.
 * Only the user-facing main Activity calls [onMainOpened]; repeated Activity instances share this gate.
 * The first main opening consumes the opportunity even if the setting is disabled or no root is selected.
 * Submission uses the same candidate request as manual sync, so the durable queue can reuse and promote it.
 */
class StartupSync(
    private val state: ApplicationStateRepository,
    private val coordinator: TaskCoordinator,
    private val authorizations: LibraryAuthorizations,
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
        request(TaskOrigin.STARTUP_SYNC)
    }

    suspend fun manualSync(): Boolean = request(TaskOrigin.MANUAL_SYNC) != null

    /**
     * A sync of the current library under its current authorization, for any backend: manual, continued
     * after re-authorization, or the stale-path sync of R11 inheriting [origin].
     */
    suspend fun request(origin: TaskOrigin): TaskId? {
        val selected = state.current() ?: return null
        if (selected.location == null) return null
        val context = authorizations.of(selected.backend).bind(selected) ?: return null
        return when (val result = coordinator.submit(TaskSubmission(
            TaskRequest.CandidateConfiguration(context, TaskRequest.CandidateConfiguration.LIBRARY_SYNC), origin,
        ))) {
            is SubmissionResult.Created -> result.taskId
            is SubmissionResult.Reused -> result.taskId
            is SubmissionResult.Promoted -> result.taskId
            is SubmissionResult.Rejected -> null
        }
    }
}
