package io.github.chenxiex.calibrecloud.tasks.onedrive

import io.github.chenxiex.calibrecloud.auth.OneDriveAuthorization
import io.github.chenxiex.calibrecloud.auth.LoginIssue
import io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationSession
import kotlinx.coroutines.withContext
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.onedrive.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*

private class StaleOneDriveSelection : RuntimeException()

/** Read-only candidate access. Browsing never activates; complete validated snapshot import does. */
class OneDriveCandidateTaskHandler(
    private val state: ApplicationStateRepository,
    private val authorization: OneDriveAuthorization,
    private val backend: OneDriveSourceBackend,
    private val results: OneDriveBrowseStore,
    private val importer: io.github.chenxiex.calibrecloud.metadata.MetadataRepository,
) : TaskHandler {
    override fun supports(request: TaskRequest) = request is TaskRequest.CandidateConfiguration &&
        request.context.backend == BackendKind.ONEDRIVE && request.operation in setOf(BROWSE, SNAPSHOT)

    override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
    override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision {
        execution.checkControl()
        return RecoveryDecision(TaskStage.CANDIDATE_ACCESS, null)
    }

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
        val request = entry.record.submission.request as TaskRequest.CandidateConfiguration
        return withContext(OneDriveAuthorizationSession(request.context.authorizationId)) { executeCandidate(entry, execution) }
    }

    private suspend fun executeCandidate(entry: QueueEntry, execution: TaskExecution): StageOutcome {
        val request = entry.record.submission.request as TaskRequest.CandidateConfiguration
        suspend fun check() {
            execution.checkControl()
            if (state.current()?.token != request.context.selectionToken) throw StaleOneDriveSelection()
            if (authorization.sessionId() != request.context.authorizationId) throw StaleOneDriveSelection()
        }
        try {
            if (authorization.sessionId() != request.context.authorizationId) return loginFailure()
            check()
            if (request.operation == BROWSE) {
                val identity = when (val result = backend.discover()) {
                    is OneDriveSourceResult.Available -> result.value
                    is OneDriveSourceResult.Failed -> return failed(result)
                }
                check()
                val location = LibraryLocation.OneDrive(identity.accountId, identity.driveId, identity.root.id)
                val parent = request.directoryItemId ?: identity.root.id
                val listing = when (val result = backend.listAllDirectories(location, parent) { check() }) {
                    is OneDriveSourceResult.Available -> result.value
                    is OneDriveSourceResult.Failed -> return failed(result)
                }
                check()
                results.save(entry.record.id, OneDriveBrowseResult(location, parent, listing.items,
                    0, false, listing.parentName.orEmpty(), complete = true))
                check()
                return StageOutcome.Complete()
            }
            val location = state.current()?.location as? LibraryLocation.OneDrive
                ?: return StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.SOURCE_MISSING)))
            // A restored location is not proof that the currently signed-in account owns this drive.
            when (val identity = backend.discover()) {
                is OneDriveSourceResult.Available -> if (identity.value.accountId != location.accountId ||
                    identity.value.driveId != location.driveId) return loginFailure()
                is OneDriveSourceResult.Failed -> return failed(identity)
            }
            check()
            return when (val snapshot = backend.acquireSnapshot(location, entry.record.id.value) { check() }) {
                is OneDriveSourceResult.Available -> {
                    check()
                    val identity = importer.importSnapshot(request.context.selectionToken, snapshot.value.file,
                        check = { check() }, taskId = entry.record.id.value)
                    if (identity == null) loginFailure() else StageOutcome.Complete(cachePublished = true)
                }
                is OneDriveSourceResult.Failed -> failed(snapshot)
            }
        } catch (failure: io.github.chenxiex.calibrecloud.metadata.SnapshotParseException) {
            return StageOutcome.Fail(TaskError.Source(StorageError(when (failure.reason) {
                io.github.chenxiex.calibrecloud.metadata.SnapshotParseFailure.INCOMPATIBLE -> StorageErrorKind.INCOMPATIBLE_DATABASE
                io.github.chenxiex.calibrecloud.metadata.SnapshotParseFailure.IO -> StorageErrorKind.LOCAL_IO
                else -> StorageErrorKind.CORRUPT_CONTENT
            })))
        } catch (_: StaleOneDriveSelection) {
            return loginFailure()
        }
    }

    private fun loginFailure() = StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.LOGIN_REQUIRED)))
    private fun failed(result: OneDriveSourceResult.Failed): StageOutcome {
        val error = if (result.error.kind == StorageErrorKind.LOGIN_REQUIRED &&
            authorization.issue in setOf(LoginIssue.NETWORK, LoginIssue.SERVER)) StorageError(StorageErrorKind.NO_NETWORK)
            else result.error
        return if (result.transient || error.kind == StorageErrorKind.NO_NETWORK)
            StageOutcome.Retry(TaskError.Source(error), result.retryDelayMillis)
            else StageOutcome.Fail(TaskError.Source(error))
    }

    companion object {
        const val BROWSE = "onedrive_browse"
        const val SNAPSHOT = "onedrive_snapshot"
        const val PAGE_SIZE = 3
    }
}
