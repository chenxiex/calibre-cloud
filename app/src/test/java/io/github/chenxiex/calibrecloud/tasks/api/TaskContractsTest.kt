package io.github.chenxiex.calibrecloud.tasks.api

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.FormatResource
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.SourceFileLocator
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TaskContractsTest {
    private val library = LibraryId(UUID.randomUUID())
    private val book = BookKey(library, 1, UUID.randomUUID())
    private val column = CustomColumnId(1, "#finished")
    private val resource = FormatResource(book, BookFormat.parse("epub"), SourceFileLocator.Local("document"))
    private val dependency = TaskDependency(TaskId(UUID.randomUUID()), DependencyRequirement.SUCCESS)

    private fun write() = TaskRequest.ReadStatusWrite(library, column, UUID.randomUUID())

    @Test
    fun originsMapToUserAndAutomaticPriorities() {
        val high = setOf(TaskOrigin.USER_DOWNLOAD, TaskOrigin.USER_OPEN, TaskOrigin.MANUAL_SYNC, TaskOrigin.USER_READ_STATUS)
        TaskOrigin.entries.forEach { assertEquals(if (it in high) TaskPriority.HIGH else TaskPriority.LOW, it.priority) }
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
        val otherRequirement = dependency.copy(requirement = DependencyRequirement.FINISHED)
        assertNotEquals(
            user.copy(dependencies = FrozenSet(listOf(dependency))).key,
            user.copy(dependencies = FrozenSet(listOf(otherRequirement))).key,
        )
    }

    @Test
    fun everyWriteIsItsOwnRequestAndKeepsItsColumn() {
        val first = write()
        assertNotEquals(TaskSubmission(first, TaskOrigin.USER_READ_STATUS).key, TaskSubmission(write(), TaskOrigin.USER_READ_STATUS).key)
        assertEquals(first, first.copy())
        assertNotEquals(first, first.copy(column = CustomColumnId(2, "#other")))
    }

    @Test
    fun submittedCollectionsAreDeduplicatedFrozenAndOrderIndependent() {
        val sourceBooks = mutableListOf(book, book)
        val sourceDependencies = mutableListOf(dependency)
        val submitted = TaskSubmission(TaskRequest.CoverLoad(library, FrozenSet(sourceBooks)), TaskOrigin.USER_OPEN, FrozenSet(sourceDependencies))
        val key = submitted.key
        sourceBooks.clear()
        sourceDependencies.clear()
        assertEquals(1, (submitted.request as TaskRequest.CoverLoad).books.size)
        assertEquals(1, submitted.dependencies.size)
        assertEquals(key, submitted.key)
        val iterator = submitted.dependencies.iterator() as MutableIterator<TaskDependency>
        iterator.next()
        assertThrows(UnsupportedOperationException::class.java) { iterator.remove() }
    }

    @Test
    fun coverBatchesRejectEmptyOrForeignLibraryBooks() {
        assertThrows(IllegalArgumentException::class.java) { TaskRequest.CoverLoad(library, FrozenSet(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            TaskRequest.CoverLoad(library, FrozenSet(listOf(book.copy(libraryId = LibraryId(UUID.randomUUID())))))
        }
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
    fun bookFailuresBelongToTheTaskLibraryOrCoverBatch() {
        val failure = BookFailure(book, TaskError.BookIdentityChanged(book))
        val record = TaskRecord(
            TaskId(UUID.randomUUID()), TaskSubmission(write(), TaskOrigin.USER_READ_STATUS),
            SchedulingPosition(TaskPriority.HIGH, QueueSequence(1)),
            state = TaskState.Finished(TaskResult.CompletedWithBookFailures(FrozenSet(listOf(failure)))),
            controls = TaskControls(false, false, false, false),
        )
        val foreign = book.copy(libraryId = LibraryId(UUID.randomUUID()))
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(state = TaskState.Finished(TaskResult.CompletedWithBookFailures(
                FrozenSet(listOf(BookFailure(foreign, TaskError.BookIdentityChanged(foreign)))),
            )))
        }
        val cover = record.copy(submission = TaskSubmission(TaskRequest.CoverLoad(book), TaskOrigin.VISIBLE_COVER),
            scheduling = SchedulingPosition(TaskPriority.LOW, QueueSequence(1)))
        val other = book.copy(sourceUuid = UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) {
            cover.copy(state = TaskState.Finished(TaskResult.CompletedWithBookFailures(
                FrozenSet(listOf(BookFailure(other, TaskError.BookIdentityChanged(other)))),
            )))
        }
    }

    @Test
    fun theSourcePushStageCannotBePausedOrCancelled() {
        val record = TaskRecord(
            TaskId(UUID.randomUUID()), TaskSubmission(write(), TaskOrigin.USER_READ_STATUS),
            SchedulingPosition(TaskPriority.HIGH, QueueSequence(1)), state = TaskState.Running(TaskStage.WRITE_COMMIT),
            controls = TaskControls(false, false, false, false),
        )
        assertThrows(IllegalArgumentException::class.java) { record.copy(controls = TaskControls(true, true, false, false)) }
        assertEquals(TaskState.Running(TaskStage.WRITE_PREPARE), record.copy(state = TaskState.Running(TaskStage.WRITE_PREPARE),
            controls = TaskControls(true, true, false, false)).state)
    }
}
