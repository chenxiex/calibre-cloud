package io.github.chenxiex.calibrecloud.tasks.sync

import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.metadata.SnapshotParseException
import io.github.chenxiex.calibrecloud.metadata.SnapshotParseFailure
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.LibrarySources
import io.github.chenxiex.calibrecloud.storage.api.SourceFailure
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private class SupersededSelection : RuntimeException()

/**
 * Selection-scoped metadata sync of any backend, followed by library activation. The request runs under
 * its bound authorization; recovery always reacquires and validates the current source, and partial files
 * never imply success. A request whose selection was replaced fails without effect; one whose
 * authorization was replaced waits like any other authorization failure, and
 * [io.github.chenxiex.calibrecloud.tasks.background.AuthorizationResume] continues it as a new request.
 */
class LibrarySyncTaskHandler(
    private val state: ApplicationStateRepository,
    private val sources: LibrarySources,
    private val authorizations: LibraryAuthorizations,
    private val importer: MetadataRepository,
    private val io: CoroutineDispatcher,
) : TaskHandler {
    override fun supports(request: TaskRequest): Boolean = request is TaskRequest.CandidateConfiguration &&
        request.operation == TaskRequest.CandidateConfiguration.LIBRARY_SYNC

    override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)

    override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision {
        execution.checkControl()
        return RecoveryDecision(TaskStage.CANDIDATE_ACCESS, null)
    }

    override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome = withContext(io) {
        val context = (entry.record.submission.request as TaskRequest.CandidateConfiguration).context
        val source = sources.of(context.backend)
        val authorization = authorizations.of(context.backend)
        val selected = state.current()
        if (selected?.token != context.selectionToken || selected.backend != context.backend) return@withContext superseded()
        if (selected.location == null) return@withContext StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.SOURCE_MISSING)))
        try {
            authorization.check(context)
            // A restored location is not proof that the current authorization may read it; the backend
            // compares the authorized account with the stored one before any request.
            val identity = authorization.within(context) {
                importer.sync(source, context.selectionToken, entry.record.id.value) {
                    execution.checkControl()
                    // A replacement may revoke the old authorization while the read is running. Stop at a chunk boundary.
                    if (state.current()?.token != context.selectionToken) throw SupersededSelection()
                    authorization.check(context)
                }
            }
            if (identity == null) superseded() else StageOutcome.Complete(cachePublished = true)
        } catch (failure: SourceFailure) {
            if (failure.transient || failure.error.kind == StorageErrorKind.NO_NETWORK)
                StageOutcome.Retry(TaskError.Source(failure.error), failure.retryDelayMillis)
            else authorizationWait(source, failure.error.kind)?.let { StageOutcome.Wait(it) }
                ?: StageOutcome.Fail(TaskError.Source(failure.error))
        } catch (failure: SnapshotParseException) {
            StageOutcome.Fail(TaskError.Source(StorageError(when (failure.reason) {
                SnapshotParseFailure.INCOMPATIBLE -> StorageErrorKind.INCOMPATIBLE_DATABASE
                SnapshotParseFailure.IO -> StorageErrorKind.LOCAL_IO
                else -> StorageErrorKind.CORRUPT_CONTENT
            })))
        } catch (_: SupersededSelection) {
            superseded()
        }
    }

    private fun superseded() = StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED)))
}
