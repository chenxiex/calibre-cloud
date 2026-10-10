package io.github.chenxiex.calibrecloud.tasks.readstatus

import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.LibrarySources
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.api.of
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import java.util.UUID

/**
 * Submits a page's "mark as read / unread" (R14, R26): the expanded, deduplicated [books] all get the
 * explicit [target]. Books are frozen from the current import and the column is the library's valid read
 * column at submission; the page's selection token is checked again, as for downloads. A library whose
 * source cannot write is rejected without a task. Submitting never changes the import or the page's
 * read state; only the sync after the push does (R13, R16).
 */
class ReadStatusService(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val sources: LibrarySources,
    private val coordinator: TaskCoordinator,
) {
    suspend fun submit(books: Collection<BookKey>, target: Boolean, selectionToken: UUID): SubmissionResult {
        val selected = state.current()
        val identity = selected?.identity
        if (selected?.token != selectionToken || identity == null || books.isEmpty()) return rejected(StorageErrorKind.VERSION_CONFLICT)
        val imported = metadata.currentImport()?.takeIf { it.identity == identity } ?: return rejected(StorageErrorKind.VERSION_CONFLICT)
        if (imported.readColumnStatus != ReadColumnStatus.VALID) return SubmissionResult.Rejected(TaskError.InvalidColumn)
        val known = imported.metadata.books.map { it.sourceId to it.sourceUuid }.toSet()
        if (books.any { it.libraryId != identity.id || (it.sourceId to it.sourceUuid) !in known }) return rejected(StorageErrorKind.VERSION_CONFLICT)
        if (sources.of(identity.location).writeCapability(identity.location) != null) return rejected(StorageErrorKind.UNSUPPORTED_OPERATION)
        if (state.current()?.token != selectionToken) return rejected(StorageErrorKind.VERSION_CONFLICT)
        return coordinator.submitReadStatus(identity.id, imported.selectedReadColumn!!, books.associateWith { target }, TaskOrigin.USER_READ_STATUS)
    }

    private fun rejected(kind: StorageErrorKind) = SubmissionResult.Rejected(TaskError.Source(StorageError(kind)))
}
