package io.github.chenxiex.calibrecloud.tasks.covers

import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import java.util.UUID

/** Explicit visible-book loading. Ordinary cache reads do not call this service. */
class CoverService(private val state: ApplicationStateRepository, private val metadata: MetadataRepository,
    private val coordinator: TaskCoordinator) {
    suspend fun submit(book: BookKey, selectionToken: UUID): SubmissionResult {
        val selected = state.current()
        val imported = metadata.currentImport()
        if (selected?.token != selectionToken || selected.identity?.id != book.libraryId ||
            imported?.identity != selected.identity || imported.metadata.books.none {
                it.sourceId == book.sourceId && it.sourceUuid == book.sourceUuid
            } || state.current()?.token != selectionToken) return SubmissionResult.Rejected(
                TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT)))
        return coordinator.submit(TaskSubmission(TaskRequest.CoverLoad(book), TaskOrigin.VISIBLE_COVER))
    }
}
