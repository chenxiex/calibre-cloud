package io.github.chenxiex.calibrecloud.tasks.copies

import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import java.util.UUID

/** Freezes an imported format path without resolving or opening source storage on the submitting side. */
class CopyService(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val coordinator: TaskCoordinator,
    private val queue: io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue,
) {
    suspend fun submit(key: CopyKey, selectionToken: UUID): SubmissionResult {
        val current = state.current()
        if (current?.token != selectionToken || current.identity?.id != key.book.libraryId) return rejected()
        val imported = metadata.currentImport()?.takeIf { it.identity == current.identity } ?: return rejected()
        val book = imported.metadata.books.find { it.sourceId == key.book.sourceId && it.sourceUuid == key.book.sourceUuid }
            ?: return rejected()
        val format = book.formats.find { it.format == key.format } ?: return rejected()
        if (state.current()?.token != selectionToken) return rejected()
        val resource = FormatResource(key.book, key.format, SourceFileLocator.Relative(imported.identity.location.backend, format.path))
        // Preserve the version premise of an existing automatic update when explicitly promoting it.
        val pending = queue.list().firstOrNull {
            val request = it.record.submission.request as? TaskRequest.FormatCopy
            it.record.state !is TaskState.Finished && request?.resource == resource
        }?.record?.submission?.request as? TaskRequest.FormatCopy
        return coordinator.submit(TaskSubmission(pending ?: TaskRequest.FormatCopy(resource), TaskOrigin.USER_DOWNLOAD))
    }
    private fun rejected() = SubmissionResult.Rejected(TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT)))
}
