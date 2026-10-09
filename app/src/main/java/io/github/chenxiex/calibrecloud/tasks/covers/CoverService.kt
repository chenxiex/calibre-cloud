package io.github.chenxiex.calibrecloud.tasks.covers

import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import java.util.UUID

/**
 * Explicit loading of a shown page's missing covers as one batch (R10). Ordinary cache reads do not call
 * this service. A new batch replaces the library's earlier unfinished page batches.
 */
class CoverService(private val state: ApplicationStateRepository, private val metadata: MetadataRepository,
    private val coordinator: TaskCoordinator, private val queue: DurableTaskQueue) {
    suspend fun submit(books: List<BookKey>, selectionToken: UUID): SubmissionResult {
        val selected = state.current()
        val imported = metadata.currentImport()
        val library = selected?.identity?.id
        val known = imported?.metadata?.books.orEmpty().map { it.sourceId to it.sourceUuid }.toSet()
        if (selected?.token != selectionToken || library == null || imported?.identity != selected.identity || books.isEmpty() ||
            books.any { it.libraryId != library || (it.sourceId to it.sourceUuid) !in known } ||
            state.current()?.token != selectionToken) return SubmissionResult.Rejected(
                TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT)))
        val result = coordinator.submit(TaskSubmission(TaskRequest.CoverLoad(library, FrozenSet(books)), TaskOrigin.VISIBLE_COVER))
        val task = when (result) {
            is SubmissionResult.Created -> result.taskId
            is SubmissionResult.Reused -> result.taskId
            is SubmissionResult.Promoted -> result.taskId
            is SubmissionResult.Rejected -> return result
        }
        queue.supersedeCoverBatches(library, task)
        return result
    }
}
