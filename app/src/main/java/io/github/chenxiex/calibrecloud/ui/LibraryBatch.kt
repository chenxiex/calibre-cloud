package io.github.chenxiex.calibrecloud.ui

import io.github.chenxiex.calibrecloud.library.FolderKey
import io.github.chenxiex.calibrecloud.library.ReadMarkBlock
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.storage.cache.CleanupPlan
import io.github.chenxiex.calibrecloud.storage.api.LibrarySources
import io.github.chenxiex.calibrecloud.storage.api.WriteBlock
import io.github.chenxiex.calibrecloud.storage.api.of
import io.github.chenxiex.calibrecloud.tasks.api.PendingRead
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.copies.CopyService
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.readstatus.ReadStatusService
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import java.util.UUID

/** Items chosen on the level that was shown when selection started; folders count by their key. */
data class LibrarySelectionSet(val books: Set<BookKey> = emptySet(), val folders: Set<FolderKey> = emptySet()) {
    val isEmpty: Boolean get() = books.isEmpty() && folders.isEmpty()
}

/**
 * A batch action that could not do all it was asked; it is posted as a system notification. Success has
 * no notice: download marks appear or disappear on the books themselves.
 */
sealed interface BatchNotice {
    /** [noFormat] books had no format to download; [rejected] submissions were refused. */
    data class Downloads(val noFormat: Int, val rejected: Int) : BatchNotice
    data object RemovalFailed : BatchNotice
    /** The selection expands to no book under the current search and filters. */
    data object NoBooks : BatchNotice
    /** The library, its import or the shown level changed so that the selection cannot be expanded. */
    data object Unavailable : BatchNotice
    /**
     * The read-state mark was not submitted: [block] when the selection or source could not take it
     * ([backend] names the authorization it needs), null when the submission was refused.
     */
    data class ReadMarkRejected(val block: ReadMarkBlock?, val backend: BackendKind? = null) : BatchNotice
}

/** Read-state writes of [books] that newly failed (R13); [error] is the first reason, null when they were cancelled. */
data class ReadFailureNotice(val books: Int, val error: TaskError?)

/**
 * A removal waiting for confirmation: the frozen [plan] (books, formats, copies, bytes) is executed as
 * shown, whatever the filters are later. [formats] is null for all formats.
 */
data class RemovalConfirmation(val plan: CleanupPlan, val books: Int, val formats: Set<BookFormat>?)

/** Side effects of batch operations; the production implementation uses the copy service and cache maintenance. */
interface LibraryBatch {
    /** Why the selected library's source cannot take a read-state write now, null when it can; never contacts a server. */
    suspend fun writeBlock(): ReadMarkBlock?

    /** Books of the selected library whose read status is still being written for its read column (R13, R27). */
    suspend fun pendingReads(): Map<BookKey, PendingRead>

    /** Submits the explicit [target] for all [books] (R14); false when the submission was refused. */
    suspend fun markRead(books: Set<BookKey>, target: Boolean, selectionToken: UUID): Boolean

    /** The page that showed these failure marks was left; they are not shown again (R13). */
    suspend fun dismissReadFailures(books: Set<BookKey>)

    /** Submits (or reuses) the user download of one format; false when it was rejected. */
    suspend fun download(key: CopyKey, selectionToken: UUID): Boolean

    suspend fun wake()

    /** Freezes the copies of [books] in [formats] (null for all) with their unfinished copy tasks. */
    suspend fun previewRemoval(books: Set<BookKey>, formats: Set<BookFormat>?): CleanupPlan?

    suspend fun remove(plan: CleanupPlan): Boolean
}

class QueueLibraryBatch(
    private val copies: CopyService,
    private val coordinator: TaskCoordinator,
    private val maintenance: CacheMaintenance,
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val sources: LibrarySources,
    private val queue: DurableTaskQueue,
    private val readStatus: ReadStatusService,
) : LibraryBatch {
    override suspend fun writeBlock(): ReadMarkBlock? {
        val location = state.current()?.identity?.location ?: return ReadMarkBlock.WRITE_AUTHORIZATION
        return when (sources.of(location).writeCapability(location)) {
            null -> null
            WriteBlock.AUTHORIZATION_REQUIRED -> ReadMarkBlock.WRITE_AUTHORIZATION
            WriteBlock.READ_ONLY_GRANT -> ReadMarkBlock.WRITE_READ_ONLY
            WriteBlock.UNSUPPORTED_PROVIDER -> ReadMarkBlock.WRITE_UNSUPPORTED
            WriteBlock.SOURCE_UNAVAILABLE -> ReadMarkBlock.WRITE_SOURCE_UNAVAILABLE
        }
    }

    override suspend fun pendingReads(): Map<BookKey, PendingRead> {
        val library = state.current()?.identity?.id ?: return emptyMap()
        val column = metadata.readStatusPreference(library)?.column ?: return emptyMap()
        return queue.pendingReadStatus(library, column)
    }

    override suspend fun markRead(books: Set<BookKey>, target: Boolean, selectionToken: UUID) =
        readStatus.submit(books, target, selectionToken) !is SubmissionResult.Rejected

    override suspend fun dismissReadFailures(books: Set<BookKey>) {
        val library = books.firstOrNull()?.libraryId ?: return
        queue.dismissShownReadFailures(library, books)
    }

    override suspend fun download(key: CopyKey, selectionToken: UUID) = copies.submit(key, selectionToken) !is SubmissionResult.Rejected

    override suspend fun wake() = coordinator.requestRun()

    override suspend fun previewRemoval(books: Set<BookKey>, formats: Set<BookFormat>?) = maintenance.previewCopies(books, formats)

    override suspend fun remove(plan: CleanupPlan) = maintenance.execute(plan)
}
