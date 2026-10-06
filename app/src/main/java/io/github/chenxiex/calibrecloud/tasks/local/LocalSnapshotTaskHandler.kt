package io.github.chenxiex.calibrecloud.tasks.local

import io.github.chenxiex.calibrecloud.metadata.*
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackend
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceResult
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private class StaleLocalSelection : RuntimeException()

/**
 * Selection-scoped read-only acquisition followed by complete metadata import and library activation.
 * Recovery always reacquires and validates the current source; partial files never imply success.
 * Local persisted grants use the selection token as a non-secret authorization context identifier.
 */
class LocalSnapshotTaskHandler(
    private val state: ApplicationStateRepository,
    private val backend: LocalSourceBackend,
    private val importer: MetadataRepository,
    private val io: CoroutineDispatcher,
) : TaskHandler {
    override fun supports(request: TaskRequest): Boolean = request is TaskRequest.CandidateConfiguration &&
        request.context.backend == BackendKind.LOCAL && request.operation == OPERATION

    override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)

    override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision {
        execution.checkControl()
        return RecoveryDecision(TaskStage.CANDIDATE_ACCESS, null)
    }

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome = withContext(io) {
        val request = entry.record.submission.request as TaskRequest.CandidateConfiguration
        val selected = state.current()
        if (selected?.token != request.context.selectionToken || selected.backend != BackendKind.LOCAL) {
            return@withContext StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED)))
        }
        val treeUri = state.localTreeUri()
            ?: return@withContext StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED)))
        if (state.current()?.token != request.context.selectionToken) return@withContext staleFailure()
        try {
            when (val result = backend.acquireSnapshot(treeUri, entry.record.id.value) {
                execution.checkControl()
                // A replacement may revoke the old grant while the read is running. Stop at a chunk boundary.
                if (state.current()?.token != request.context.selectionToken) throw StaleLocalSelection()
            }) {
                is LocalSourceResult.Available -> {
                    val identity = importer.importSnapshot(request.context.selectionToken, result.value.file,
                        check = { execution.checkControl() }, taskId = entry.record.id.value)
                    if (identity == null) staleFailure() else StageOutcome.Complete(cachePublished = true)
                }
                is LocalSourceResult.Failed -> StageOutcome.Fail(TaskError.Source(result.error))
            }
        } catch (failure: SnapshotParseException) {
            StageOutcome.Fail(TaskError.Source(StorageError(when (failure.reason) {
                SnapshotParseFailure.INCOMPATIBLE -> StorageErrorKind.INCOMPATIBLE_DATABASE
                SnapshotParseFailure.IO -> StorageErrorKind.LOCAL_IO
                else -> StorageErrorKind.CORRUPT_CONTENT
            })))
        } catch (_: StaleLocalSelection) {
            staleFailure()
        }
    }

    private fun staleFailure() = StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED)))

    companion object { const val OPERATION = "local_snapshot" }
}
