package io.github.chenxiex.calibrecloud.tasks.onedrive

import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.storage.onedrive.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*

private class StaleOneDriveSelection : RuntimeException()

/**
 * OneDrive root selection: lists directories of the signed-in drive and never activates a library.
 * Syncing the chosen root is the common library sync.
 */
class OneDriveCandidateTaskHandler(
    private val state: ApplicationStateRepository,
    private val authorization: LibraryAuthorization,
    private val backend: OneDriveSourceBackend,
    private val source: LibrarySource,
    private val results: OneDriveBrowseStore,
) : TaskHandler {
    override fun supports(request: TaskRequest) = request is TaskRequest.CandidateConfiguration &&
        request.context.backend == BackendKind.ONEDRIVE && request.operation == BROWSE

    override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
    override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision {
        execution.checkControl()
        return RecoveryDecision(TaskStage.CANDIDATE_ACCESS, null)
    }

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
        val request = entry.record.submission.request as TaskRequest.CandidateConfiguration
        suspend fun check() {
            execution.checkControl()
            if (state.current()?.token != request.context.selectionToken) throw StaleOneDriveSelection()
            authorization.check(request.context)
        }
        return try {
            check()
            authorization.within(request.context) {
                val identity = when (val result = backend.discover()) {
                    is OneDriveSourceResult.Available -> result.value
                    is OneDriveSourceResult.Failed -> throw SourceFailure(result.error, result.transient, result.retryDelayMillis)
                }
                check()
                val location = LibraryLocation.OneDrive(identity.accountId, identity.driveId, identity.root.id)
                val parent = request.directoryItemId ?: identity.root.id
                val listing = when (val result = backend.listAllDirectories(location, parent) { check() }) {
                    is OneDriveSourceResult.Available -> result.value
                    is OneDriveSourceResult.Failed -> throw SourceFailure(result.error, result.transient, result.retryDelayMillis)
                }
                check()
                results.save(entry.record.id, OneDriveBrowseResult(location, parent, listing.items,
                    0, false, listing.parentName.orEmpty(), complete = true))
                check()
            }
            StageOutcome.Complete()
        } catch (failure: SourceFailure) {
            if (failure.transient || failure.error.kind == StorageErrorKind.NO_NETWORK)
                StageOutcome.Retry(TaskError.Source(failure.error), failure.retryDelayMillis)
            else authorizationWait(source, failure.error.kind)?.let { StageOutcome.Wait(it) }
                ?: StageOutcome.Fail(TaskError.Source(failure.error))
        } catch (_: StaleOneDriveSelection) {
            // The browser belongs to the replaced selection; a later login continues it for the current one.
            StageOutcome.Wait(WaitingReason.LOGIN)
        }
    }

    companion object {
        const val BROWSE = "onedrive_browse"
        const val PAGE_SIZE = 3
    }
}
