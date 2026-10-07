package io.github.chenxiex.calibrecloud.tasks.api

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.FormatResource
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.SourceFileLocator
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskContractsTest {
    private val library = LibraryId(UUID.randomUUID())
    private val book = BookKey(library, 1, UUID.randomUUID())
    private val column = CustomColumnId(1, "#finished")
    private val resource = FormatResource(book, BookFormat.parse("epub"), SourceFileLocator.Local("document"))
    private val dependency = TaskDependency(TaskId(UUID.randomUUID()), DependencyRequirement.SUCCESS)

    private fun write(target: Boolean, books: Collection<BookKey> = listOf(book)) =
        TaskRequest.ReadStatusWrite(library, FrozenSet(books), column, target)

    @Test
    fun originsMapToUserAndAutomaticPriorities() {
        val high = setOf(TaskOrigin.USER_DOWNLOAD, TaskOrigin.USER_OPEN, TaskOrigin.MANUAL_SYNC, TaskOrigin.USER_READ_STATUS)
        TaskOrigin.entries.forEach { assertEquals(if (it in high) TaskPriority.HIGH else TaskPriority.LOW, it.priority) }
    }

    @Test
    fun necessaryPreparationInheritsParentSourceAndPriority() {
        val preparation = TaskSubmission(TaskRequest.MetadataSync(library), TaskOrigin.USER_DOWNLOAD)
        assertEquals(TaskPriority.HIGH, preparation.origin.priority)
    }

    @Test
    fun schedulingOrdersPriorityThenSequenceWithoutOverflow() {
        val low = SchedulingPosition(TaskPriority.LOW, QueueSequence(0))
        val first = SchedulingPosition(TaskPriority.HIGH, QueueSequence(1))
        val last = SchedulingPosition(TaskPriority.HIGH, QueueSequence(Long.MAX_VALUE))
        assertTrue(compareSchedulingPositions(last, low) < 0)
        assertTrue(compareSchedulingPositions(first, last) < 0)
        assertTrue(compareSchedulingPositions(last, first) > 0)
        assertEquals(0, compareSchedulingPositions(first, first))
    }

    @Test
    fun requestKeysIsolateLibraryUuidFormatLocatorAndComparisonPremise() {
        val request = TaskRequest.FormatCopy(resource)
        val key = TaskSubmission(request, TaskOrigin.USER_DOWNLOAD).key
        val variants = listOf(
            resource.copy(book = book.copy(libraryId = LibraryId(UUID.randomUUID()))),
            resource.copy(book = book.copy(sourceUuid = UUID.randomUUID())),
            resource.copy(format = BookFormat.parse("pdf")),
            resource.copy(source = SourceFileLocator.Local("other")),
        )
        variants.forEach { assertNotEquals(key, TaskSubmission(TaskRequest.FormatCopy(it), TaskOrigin.USER_DOWNLOAD).key) }
        val versioned = TaskRequest.FormatCopy(resource, FileVersion(BackendKind.LOCAL, "version-a"))
        assertNotEquals(key, TaskSubmission(versioned, TaskOrigin.USER_DOWNLOAD).key)
        assertNotEquals(versioned, versioned.copy(expectedVersion = FileVersion(BackendKind.LOCAL, "version-b")))
    }

    @Test
    fun dependenciesDistinguishEquivalenceButOriginAllowsPromotion() {
        val request = TaskRequest.FormatCopy(resource)
        val automatic = TaskSubmission(request, TaskOrigin.DOWNLOADED_FORMAT_UPDATE)
        val user = TaskSubmission(request, TaskOrigin.USER_DOWNLOAD)
        assertEquals(automatic.key, user.key)
        assertNotEquals(user.key, user.copy(dependencies = FrozenSet(listOf(dependency))).key)
        val otherRequirement = dependency.copy(requirement = DependencyRequirement.SAFE_TERMINAL)
        assertNotEquals(
            user.copy(dependencies = FrozenSet(listOf(dependency))).key,
            user.copy(dependencies = FrozenSet(listOf(otherRequirement))).key,
        )
    }

    @Test
    fun oppositeTargetsNeverMergeButShareOrderingKeysForOverlappingBooks() {
        val read = write(true)
        val unread = write(false)
        assertNotEquals(TaskSubmission(read, TaskOrigin.USER_READ_STATUS).key, TaskSubmission(unread, TaskOrigin.USER_READ_STATUS).key)
        assertEquals(read.relatedWriteKeys(), unread.relatedWriteKeys())
        val other = BookKey(library, 2, UUID.randomUUID())
        assertTrue(read.relatedWriteKeys().intersect(write(false, listOf(book, other)).relatedWriteKeys()).isNotEmpty())
        assertNotEquals(read, read.copy(column = CustomColumnId(2, "#other")))
    }

    @Test
    fun writeRefetchCannotReuseEarlierSyncOrAnotherWriteBarrier() {
        val writeId = TaskId(UUID.randomUUID())
        val refresh = TaskSubmission(
            TaskRequest.MetadataSync(library, SnapshotFreshness.AfterWrite(writeId)),
            TaskOrigin.USER_READ_STATUS,
            FrozenSet(listOf(TaskDependency(writeId, DependencyRequirement.SOURCE_COMMIT_CONFIRMED))),
        )
        assertNotEquals(TaskSubmission(TaskRequest.MetadataSync(library), TaskOrigin.MANUAL_SYNC).key, refresh.key)
        assertNotEquals(refresh.request, TaskRequest.MetadataSync(library, SnapshotFreshness.AfterWrite(TaskId(UUID.randomUUID()))))
        assertEquals(TaskPriority.HIGH, refresh.origin.priority)
        assertThrows(IllegalArgumentException::class.java) { refresh.copy(dependencies = FrozenSet(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { refresh.copy(origin = TaskOrigin.STARTUP_SYNC) }
    }

    @Test
    fun submittedCollectionsAreDeduplicatedFrozenAndOrderIndependent() {
        val sourceBooks = mutableListOf(book, book)
        val sourceDependencies = mutableListOf(dependency)
        val submitted = TaskSubmission(write(true, sourceBooks), TaskOrigin.USER_READ_STATUS, FrozenSet(sourceDependencies))
        val key = submitted.key
        sourceBooks.clear()
        sourceDependencies.clear()
        assertEquals(1, (submitted.request as TaskRequest.ReadStatusWrite).books.size)
        assertEquals(1, submitted.dependencies.size)
        assertEquals(key, submitted.key)
        val other = BookKey(library, 2, UUID.randomUUID())
        assertEquals(write(true, listOf(book, other)), write(true, listOf(other, book)))
        val iterator = submitted.dependencies.iterator() as MutableIterator<TaskDependency>
        iterator.next()
        assertThrows(UnsupportedOperationException::class.java) { iterator.remove() }
    }

    @Test
    fun writesRejectEmptyOrForeignLibraryTargets() {
        assertThrows(IllegalArgumentException::class.java) { write(true, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { write(false, listOf(book.copy(libraryId = LibraryId(UUID.randomUUID())))) }
    }

    @Test
    fun promotionPreservesOriginalOriginAndUsesNewUserQueuePosition() {
        val promotion = PriorityPromotion(TaskOrigin.USER_OPEN, QueueSequence(12))
        val record = TaskRecord(
            TaskId(UUID.randomUUID()), TaskSubmission(TaskRequest.FormatCopy(resource), TaskOrigin.DOWNLOADED_FORMAT_UPDATE),
            SchedulingPosition(TaskPriority.HIGH, promotion.sequence), promotion, TaskState.Queued,
            TaskControls(false, true, false, false),
        )
        assertEquals(TaskOrigin.DOWNLOADED_FORMAT_UPDATE, record.originalOrigin)
        assertEquals(TaskOrigin.USER_OPEN, record.effectiveOrigin)
        assertThrows(IllegalArgumentException::class.java) { record.copy(scheduling = record.scheduling.copy(sequence = QueueSequence(0))) }
        assertThrows(IllegalArgumentException::class.java) { PriorityPromotion(TaskOrigin.STARTUP_SYNC, QueueSequence(13)) }
    }

    @Test
    fun partialWriteResultsIdentifyOnlySubmittedFailedBooksAndRequireCommitEvidence() {
        val failure = BookFailure(book, TaskError.BookIdentityChanged(book))
        val result = TaskResult.CompletedWithBookFailures(FrozenSet(listOf(failure)))
        val record = TaskRecord(
            TaskId(UUID.randomUUID()), TaskSubmission(write(true), TaskOrigin.USER_READ_STATUS),
            SchedulingPosition(TaskPriority.HIGH, QueueSequence(1)), state = TaskState.Finished(result),
            controls = TaskControls(false, false, false, false), commit = CommitState.Confirmed,
        )
        assertEquals(FrozenSet(listOf(failure)), (record.state as TaskState.Finished).let {
            (it.result as TaskResult.CompletedWithBookFailures).failures
        })
        assertThrows(IllegalArgumentException::class.java) { record.copy(commit = CommitState.NotCommitted) }
        val foreign = book.copy(sourceUuid = UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(state = TaskState.Finished(TaskResult.CompletedWithBookFailures(
                FrozenSet(listOf(BookFailure(foreign, TaskError.BookIdentityChanged(foreign)))),
            )))
        }
    }

    @Test
    fun failuresAndCancellationRetainCommitEvidenceAndCorrectRetryBoundary() {
        val error = TaskError.Source(StorageError(StorageErrorKind.NO_NETWORK))
        val before = StageFailure(TaskStage.WRITE_SNAPSHOT, error, CommitState.NotCommitted)
        val after = StageFailure(TaskStage.WRITE_REFETCH, error, CommitState.Confirmed)
        val unknown = StageFailure(TaskStage.WRITE_COMMIT, error, CommitState.Unknown(UUID.randomUUID()))
        assertEquals(RetryFrom.FAILED_STAGE, before.retryFrom)
        assertEquals(RetryFrom.WRITE_REFETCH, after.retryFrom)
        assertEquals(RetryFrom.RECOVERY_CHECK, unknown.retryFrom)
        val record = TaskRecord(
            TaskId(UUID.randomUUID()), TaskSubmission(write(true), TaskOrigin.USER_READ_STATUS),
            SchedulingPosition(TaskPriority.HIGH, QueueSequence(1)), state = TaskState.Finished(TaskResult.Failed(after)),
            controls = TaskControls(false, false, false, true), commit = CommitState.Confirmed,
        )
        assertThrows(IllegalArgumentException::class.java) { record.copy(commit = CommitState.NotCommitted) }
        assertEquals(CommitState.Confirmed, record.copy(state = TaskState.Finished(TaskResult.Cancelled(CommitState.Confirmed))).commit)
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(state = TaskState.Running(TaskStage.WRITE_COMMIT), controls = TaskControls(true, true, false, false))
        }
    }
}
