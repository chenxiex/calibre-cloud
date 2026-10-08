package io.github.chenxiex.calibrecloud.ui

import io.github.chenxiex.calibrecloud.library.FolderKey
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.storage.cache.CleanupPlan
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.copies.CopyService
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
}

/**
 * A removal waiting for confirmation: the frozen [plan] (books, formats, copies, bytes) is executed as
 * shown, whatever the filters are later. [formats] is null for all formats.
 */
data class RemovalConfirmation(val plan: CleanupPlan, val books: Int, val formats: Set<BookFormat>?)

/** Side effects of batch operations; the production implementation uses the copy service and cache maintenance. */
interface LibraryBatch {
    /** Whether a read-state mark can be submitted; false until source write-back exists (phase 4). */
    val readWriteAvailable: Boolean

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
) : LibraryBatch {
    override val readWriteAvailable = false

    override suspend fun download(key: CopyKey, selectionToken: UUID) = copies.submit(key, selectionToken) !is SubmissionResult.Rejected

    override suspend fun wake() = coordinator.requestRun()

    override suspend fun previewRemoval(books: Set<BookKey>, formats: Set<BookFormat>?) = maintenance.previewCopies(books, formats)

    override suspend fun remove(plan: CleanupPlan) = maintenance.execute(plan)
}
