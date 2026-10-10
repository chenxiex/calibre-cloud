package io.github.chenxiex.calibrecloud.tasks

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.QueueEntry
import io.github.chenxiex.calibrecloud.tasks.persistence.RecoveryCheckpoint
import io.github.chenxiex.calibrecloud.tasks.persistence.RecoveryDecision
import io.github.chenxiex.calibrecloud.tasks.persistence.StageOutcome
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskExecution
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android SQLite; handlers are protocol fixtures and never access a source library. */
@RunWith(AndroidJUnit4::class)
class DurableTaskQueueTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var identity: LibraryIdentity

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "task-test-${UUID.randomUUID()}.db"
        reopen()
        identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", "fixture-root"), UUID.randomUUID())
        assertTrue(state.bindValidated(state.select(identity.location).token, identity))
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun promotionAndQueueSequenceSurviveReopen() = runBlocking<Unit> {
        val automatic = created(sync(TaskOrigin.STARTUP_SYNC))
        val earlier = created(cover(2, TaskOrigin.USER_DOWNLOAD))
        val promoted = queue.submit(sync(TaskOrigin.MANUAL_SYNC)) as SubmissionResult.Promoted
        assertEquals(automatic, promoted.taskId)
        assertEquals(SubmissionResult.Reused(automatic), queue.submit(sync(TaskOrigin.MANUAL_SYNC)))
        val before = queue.list()
        assertEquals(listOf(earlier, automatic), before.map { it.record.id })
        val record = queue.get(automatic)!!.record
        assertEquals(TaskOrigin.STARTUP_SYNC, record.originalOrigin)
        assertEquals(TaskOrigin.MANUAL_SYNC, record.effectiveOrigin)
        database.close()
        reopen()
        assertEquals(before, queue.list())
        val later = created(cover(3, TaskOrigin.USER_DOWNLOAD))
        assertTrue(queue.get(later)!!.record.scheduling.sequence.value > record.scheduling.sequence.value)
    }

    @Test
    fun concurrentEquivalentSubmissionsCreateOnlyOneDurableRequest() = runBlocking<Unit> {
        val outcomes = coroutineScope {
            (1..12).map { async(Dispatchers.Default) { queue.submit(sync(TaskOrigin.STARTUP_SYNC)) } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it is SubmissionResult.Created })
        assertEquals(11, outcomes.count { it is SubmissionResult.Reused })
        assertEquals(1, queue.list().size)
        database.close()
        reopen()
        assertEquals(1, queue.list().size)
    }

    @Test
    fun dependentPromotionRaisesPrerequisiteAfterExistingHighPriorityWork() = runBlocking<Unit> {
        val prerequisite = created(sync(TaskOrigin.STARTUP_SYNC))
        val dependentSubmission = cover(1, TaskOrigin.VISIBLE_COVER).copy(dependencies = FrozenSet(listOf(TaskDependency(prerequisite, DependencyRequirement.SUCCESS))))
        val dependent = created(dependentSubmission)
        val earlier = created(cover(2, TaskOrigin.USER_DOWNLOAD))
        queue.submit(dependentSubmission.copy(origin = TaskOrigin.USER_OPEN))
        assertEquals(listOf(earlier, prerequisite, dependent), queue.list().map { it.record.id })
        assertEquals(TaskOrigin.STARTUP_SYNC, queue.get(prerequisite)!!.record.originalOrigin)
        assertEquals(TaskPriority.HIGH, queue.get(prerequisite)!!.record.scheduling.priority)
        assertEquals(TaskPriority.HIGH, queue.get(dependent)!!.record.scheduling.priority)
    }

    @Test
    fun waitingDependencyDoesNotBlockUnrelatedEligibleWork() = runBlocking<Unit> {
        val prerequisite = created(sync(TaskOrigin.STARTUP_SYNC))
        val dependent = created(cover(1, TaskOrigin.USER_OPEN).copy(dependencies = FrozenSet(listOf(TaskDependency(prerequisite, DependencyRequirement.SUCCESS)))))
        val unrelated = created(cover(2, TaskOrigin.USER_DOWNLOAD))
        val chosen = queue.claim(0, { if (it.id == prerequisite) setOf(WaitingReason.NETWORK) else emptySet() }, { true })!!
        assertEquals(unrelated, chosen.record.id)
        assertTrue(WaitingReason.DEPENDENCY in (queue.get(dependent)!!.record.state as TaskState.Waiting).reasons)
        assertTrue(WaitingReason.NETWORK in (queue.get(prerequisite)!!.record.state as TaskState.Waiting).reasons)
    }

    @Test
    fun runningTaskIsNotPreemptedAndNewHighPriorityWinsNextSelection() = runBlocking<Unit> {
        val first = created(cover(1, TaskOrigin.VISIBLE_COVER))
        val second = created(cover(2, TaskOrigin.VISIBLE_COVER))
        assertEquals(first, queue.claim(0, { emptySet() }, { true })!!.record.id)
        val high = created(sync(TaskOrigin.MANUAL_SYNC))
        assertNull(queue.claim(0, { emptySet() }, { true }))
        finish(first)
        assertEquals(high, queue.claim(0, { emptySet() }, { true })!!.record.id)
        assertEquals(TaskState.Queued, queue.get(second)!!.record.state)
    }

    @Test
    fun sourceVersionsArePersistedWithoutMerging() = runBlocking<Unit> {
        val resource = FormatResource(book(1), BookFormat.parse("EPUB"), SourceFileLocator.Local("fixture-book"))
        val first = created(TaskSubmission(TaskRequest.FormatCopy(resource, FileVersion(BackendKind.LOCAL, "one")), TaskOrigin.USER_DOWNLOAD))
        val second = created(TaskSubmission(TaskRequest.FormatCopy(resource, FileVersion(BackendKind.LOCAL, "two")), TaskOrigin.USER_DOWNLOAD))
        assertNotEquals(first, second)
        database.close()
        reopen()
        assertEquals("one", (queue.get(first)!!.record.submission.request as TaskRequest.FormatCopy).expectedVersion!!.token)
    }

    @Test
    fun crossLibraryDependenciesAndWritesWithoutChangeListsAreRejectedTransactionally() = runBlocking<Unit> {
        val parent = created(sync(TaskOrigin.MANUAL_SYNC))
        val before = queue.list()
        val foreign = TaskSubmission(TaskRequest.CoverLoad(BookKey(LibraryId(UUID.randomUUID()), 1, UUID.randomUUID())), TaskOrigin.MANUAL_SYNC,
            FrozenSet(listOf(TaskDependency(parent, DependencyRequirement.SUCCESS))))
        assertTrue(queue.submit(foreign) is SubmissionResult.Rejected)
        val bare = TaskSubmission(TaskRequest.ReadStatusWrite(identity.id, column, UUID.randomUUID()), TaskOrigin.USER_READ_STATUS)
        assertTrue(queue.submit(bare) is SubmissionResult.Rejected)
        assertEquals(before, queue.list())
        database.close()
        reopen()
        assertEquals(before, queue.list())
    }

    @Test
    fun interruptedStageRetainsCheckpointAndRequiresRevalidationAfterReopen() = runBlocking<Unit> {
        val task = created(cover(1, TaskOrigin.VISIBLE_COVER))
        queue.claim(0, { emptySet() }, { true })
        val checkpoint = RecoveryCheckpoint(UUID.randomUUID(), FileVersion(BackendKind.LOCAL, "fixture-version"))
        queue.update(task, action = { entry -> entry.copy(stage = TaskStage.COVER_PUBLISH, checkpoint = checkpoint, attempts = 2, retryAt = 900,
            record = entry.record.copy(state = TaskState.Running(TaskStage.COVER_PUBLISH))) })
        database.close()
        reopen()
        queue.recover()
        val restored = queue.get(task)!!
        assertEquals(TaskStage.COVER_PUBLISH, restored.stage)
        assertEquals(checkpoint, restored.checkpoint)
        assertEquals(2, restored.attempts)
        assertEquals(900L, restored.retryAt)
        assertTrue(restored.recoveryRequired)
        assertEquals(TaskState.Queued, restored.record.state)
        assertNull(queue.claim(899, { emptySet() }, { true }))
        assertEquals(task, queue.claim(900, { emptySet() }, { true })!!.record.id)
    }

    @Test
    fun candidateRunsWithoutValidatedIdentityAndOldSelectionCannotExecute() = runBlocking<Unit> {
        val selection = state.select(identity.location)
        val candidate = created(TaskSubmission(TaskRequest.CandidateConfiguration(
            CandidateContext(selection.token, BackendKind.LOCAL, UUID.randomUUID()), "validate"), TaskOrigin.MANUAL_SYNC))
        assertNull(state.current()!!.identity)
        assertEquals(candidate, queue.claim(0, { emptySet() }, { true })!!.record.id)
        queue.update(candidate, action = { entry -> entry.copy(record = entry.record.copy(state = TaskState.Queued)) })
        state.select(LibraryLocation.Local("test.documents", "new-fixture-root"))
        database.close()
        reopen()
        assertNull(queue.claim(0, { emptySet() }, { true }))
        assertTrue(WaitingReason.INACTIVE_LIBRARY in (queue.get(candidate)!!.record.state as TaskState.Waiting).reasons)
        assertFalse(state.bindValidated(selection.token, identity))
    }

    @Test
    fun concurrentCoordinatorWakeupsNeverOverlapAndReselectBetweenResources() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executed = mutableListOf<TaskId>()
        var active = 0
        var maximum = 0
        val first = created(cover(1, TaskOrigin.VISIBLE_COVER))
        val second = created(cover(2, TaskOrigin.VISIBLE_COVER))
        val handler = FixtureHandler { entry, _ ->
            active++
            maximum = maxOf(maximum, active)
            try {
                if (entry.record.id == first && entry.stage == TaskStage.COVER_TRANSFER) {
                    started.complete(Unit)
                    release.await()
                }
                if (entry.stage == TaskStage.COVER_TRANSFER) executed.add(entry.record.id)
                next(entry)
            } finally { active-- }
        }
        val coordinator = TaskCoordinator(queue, listOf(handler))
        val firstWakeup = launch(Dispatchers.Default) { coordinator.drain() }
        withTimeout(10_000) { started.await() }
        val high = created(cover(3, TaskOrigin.USER_DOWNLOAD))
        val secondWakeup = launch(Dispatchers.Default) { TaskCoordinator(queue, listOf(handler)).drain() }
        assertEquals(first, queue.list().single { it.record.state is TaskState.Running }.record.id)
        release.complete(Unit)
        withTimeout(10_000) { firstWakeup.join() }
        withTimeout(10_000) { secondWakeup.join() }
        assertEquals(1, maximum)
        assertEquals(listOf(first, high, second), executed)
        assertTrue(queue.list().all { it.record.state == TaskState.Finished(TaskResult.Completed) })
    }

    @Test
    fun cooperativePauseAndCancelStopHandlerBeforePublication() = runBlocking<Unit> {
        for (command in listOf(TaskControl.PAUSE, TaskControl.CANCEL)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var resourceClosed = false
            var published = false
            val task = created(cover(if (command == TaskControl.PAUSE) 1 else 2, TaskOrigin.USER_DOWNLOAD))
            val handler = FixtureHandler { entry, execution ->
                if (entry.stage == TaskStage.COVER_TRANSFER) {
                    try {
                        started.complete(Unit)
                        release.await()
                        execution.checkControl()
                    } finally { resourceClosed = true }
                } else published = true
                next(entry)
            }
            val coordinator = TaskCoordinator(queue, listOf(handler))
            val running = launch(Dispatchers.Default) { coordinator.drain() }
            withTimeout(10_000) { started.await() }
            assertTrue(queue.control(task, command))
            release.complete(Unit)
            withTimeout(10_000) { running.join() }
            assertTrue(resourceClosed)
            assertFalse(published)
            if (command == TaskControl.PAUSE) {
                assertEquals(TaskState.Paused(TaskStage.COVER_TRANSFER), queue.get(task)!!.record.state)
                assertTrue(queue.control(task, TaskControl.CANCEL))
            } else assertEquals(TaskState.Finished(TaskResult.Cancelled), queue.get(task)!!.record.state)
        }
    }

    @Test
    fun processInterruptionRequiresRecoveryDecisionBeforeContinuingSavedStage() = runBlocking<Unit> {
        val task = created(cover(1, TaskOrigin.USER_DOWNLOAD))
        val started = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val checkpoint = RecoveryCheckpoint(UUID.randomUUID(), FileVersion(BackendKind.LOCAL, "version"))
        val interrupted = FixtureHandler { entry, execution ->
            if (entry.stage == TaskStage.COVER_PUBLISH) {
                execution.checkpoint(checkpoint)
                started.complete(Unit)
                neverRelease.await()
            }
            next(entry)
        }
        val running = launch(Dispatchers.Default) { TaskCoordinator(queue, listOf(interrupted)).drain() }
        withTimeout(10_000) { started.await() }
        running.cancelAndJoin()
        database.close()
        reopen()
        val stages = mutableListOf<TaskStage>()
        var recovered = false
        val resumed = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.CoverLoad
            override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution): RecoveryDecision {
                assertEquals(checkpoint, entry.checkpoint)
                assertEquals(TaskStage.COVER_PUBLISH, entry.stage)
                recovered = true
                // A real handler checks version and staging here; this fixture explicitly approves them.
                return RecoveryDecision(entry.stage, entry.checkpoint)
            }
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                assertTrue(recovered)
                stages.add(entry.stage)
                return next(entry)
            }
        }
        TaskCoordinator(queue, listOf(resumed)).drain()
        assertEquals(listOf(TaskStage.COVER_PUBLISH), stages)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
    }

    @Test
    fun serverThrottlingBackoffWaitsForThrottlingNotNetwork() = runBlocking<Unit> {
        var time = 0L
        var calls = 0
        val task = created(cover(1, TaskOrigin.USER_DOWNLOAD))
        val handler = FixtureHandler { _, _ ->
            calls++
            StageOutcome.Retry(TaskError.Source(StorageError(StorageErrorKind.THROTTLED)), serverDelayMillis = 2500)
        }
        val coordinator = TaskCoordinator(queue, listOf(handler), now = { time })
        coordinator.drain()
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.THROTTLED))), queue.get(task)!!.record.state)
        // A claim during backoff keeps the throttling reason instead of reporting a network wait.
        coordinator.drain()
        assertEquals(1, calls)
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.THROTTLED))), queue.get(task)!!.record.state)
        repeat(3) {
            time = queue.get(task)!!.retryAt
            coordinator.drain()
        }
        assertEquals(StorageErrorKind.THROTTLED, (((queue.get(task)!!.record.state as TaskState.Finished).result
            as TaskResult.Failed).failure.error as TaskError.Source).error.kind)
    }

    @Test
    fun transientFailureHasPersistedFiniteBackoffAndPermanentErrorNeedsExplicitRetry() = runBlocking<Unit> {
        var time = 0L
        var calls = 0
        val task = created(cover(1, TaskOrigin.USER_DOWNLOAD))
        val handler = FixtureHandler { _, _ ->
            calls++
            StageOutcome.Retry(TaskError.Source(StorageError(StorageErrorKind.NO_NETWORK)), serverDelayMillis = 2500)
        }
        var coordinator = TaskCoordinator(queue, listOf(handler), now = { time })
        coordinator.drain()
        assertEquals(2500L, queue.get(task)!!.retryAt)
        assertEquals(FrozenSet(listOf(WaitingReason.NETWORK)), (queue.get(task)!!.record.state as TaskState.Waiting).reasons)
        database.close()
        reopen()
        coordinator = TaskCoordinator(queue, listOf(handler), now = { time })
        coordinator.drain()
        assertEquals(1, calls)
        repeat(3) {
            time = queue.get(task)!!.retryAt
            coordinator.drain()
        }
        assertEquals(4, calls)
        assertTrue(queue.get(task)!!.record.state is TaskState.Finished)
        coordinator.drain()
        assertEquals(4, calls)
        assertTrue(queue.control(task, TaskControl.RETRY))
        var permanentCalls = 0
        val permanent = FixtureHandler { _, _ ->
            permanentCalls++
            StageOutcome.Fail(TaskError.Source(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED)))
        }
        TaskCoordinator(queue, listOf(permanent), now = { time }).drain()
        TaskCoordinator(queue, listOf(permanent), now = { time + 100000 }).drain()
        assertEquals(1, permanentCalls)
    }

    @Test
    fun cacheEventsRequireFinalStageAndFollowDurableCompletion() = runBlocking<Unit> {
        val events = mutableListOf<TaskEvent>()
        val observed = launch(start = CoroutineStart.UNDISPATCHED) { queue.events.collect { events.add(it) } }
        val incomplete = created(copy(1))
        TaskCoordinator(queue, listOf(FixtureHandler { _, _ -> StageOutcome.Complete(cachePublished = true) })).drain()
        yield()
        assertTrue(queue.get(incomplete)!!.record.state is TaskState.Finished)
        assertTrue(events.none { it is TaskEvent.CacheChanged })
        val complete = created(copy(2))
        TaskCoordinator(queue, listOf(FixtureHandler { entry, _ ->
            if (entry.stage == TaskStage.FORMAT_PUBLISH) StageOutcome.Complete(cachePublished = true) else next(entry)
        })).drain()
        yield()
        observed.cancelAndJoin()
        val publication = events.filterIsInstance<TaskEvent.CacheChanged>().single()
        assertEquals(complete, publication.taskId)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(publication.taskId)!!.record.state)
        val publicationIndex = events.indexOf(publication)
        assertTrue(events.take(publicationIndex).filterIsInstance<TaskEvent.Changed>().any {
            it.record.id == complete && it.record.state == TaskState.Finished(TaskResult.Completed)
        })
    }

    @Test
    fun previousLibraryWriteDoesNotBlockNewLibrary() = runBlocking<Unit> {
        val oldWrite = mark(1L to true)
        val replacement = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("test.documents", "other-fixture-root"), UUID.randomUUID())
        assertTrue(state.bindValidated(state.select(replacement.location).token, replacement))
        val newTask = created(TaskSubmission(TaskRequest.CoverLoad(BookKey(replacement.id, 1, UUID.randomUUID())), TaskOrigin.USER_OPEN))
        assertEquals(newTask, queue.claim(0, { emptySet() }, { true })!!.record.id)
        assertTrue(WaitingReason.INACTIVE_LIBRARY in (queue.get(oldWrite)!!.record.state as TaskState.Waiting).reasons)
    }

    @Test
    fun reselectDuringCandidateExecutionCannotCompleteStaleValidation() = runBlocking<Unit> {
        val selected = state.select(identity.location)
        val candidate = created(TaskSubmission(TaskRequest.CandidateConfiguration(
            CandidateContext(selected.token, BackendKind.LOCAL, UUID.randomUUID()), "validate"), TaskOrigin.MANUAL_SYNC))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handler = FixtureHandler { _, _ ->
            started.complete(Unit)
            release.await()
            StageOutcome.Complete()
        }
        val running = launch(Dispatchers.Default) { TaskCoordinator(queue, listOf(handler)).drain() }
        withTimeout(10_000) { started.await() }
        val newer = state.select(LibraryLocation.Local("test.documents", "new-fixture-root"))
        release.complete(Unit)
        withTimeout(10_000) { running.join() }
        assertEquals(newer, state.current())
        assertEquals(TaskState.Finished(TaskResult.Cancelled), queue.get(candidate)!!.record.state)
    }

    @Test
    fun readStatusClicksMergeUntilTheWriteStartsAndTheLastTargetWins() = runBlocking<Unit> {
        val write = mark(1L to true, 2L to true)
        assertEquals(SubmissionResult.Reused(write), submitMark(1L to false))
        assertEquals(mapOf(book(1) to false, book(2) to true), queue.readStatusChanges(write))
        database.close()
        reopen()
        assertEquals(mapOf(book(1) to false, book(2) to true), queue.readStatusChanges(write))
        assertEquals(TaskPriority.HIGH, queue.get(write)!!.record.scheduling.priority)
        assertEquals(write, queue.claim(0, { emptySet() }, { true })!!.record.id)
        // Once started, the list is fixed: a click goes to a new write after it.
        val later = mark(2L to false)
        assertNotEquals(write, later)
        assertEquals(mapOf(book(1) to false, book(2) to true), queue.readStatusChanges(write))
        assertEquals(mapOf(book(2) to false), queue.readStatusChanges(later))
        assertEquals(setOf(TaskDependency(write, DependencyRequirement.FINISHED)), queue.get(later)!!.record.submission.dependencies.toSet())
        // The newest row of a book is what pages show.
        assertEquals(pendingOf(1L to PendingRead.Pending(false), 2L to PendingRead.Pending(false)), pending())
        assertEquals(SubmissionResult.Reused(later), submitMark(3L to true))
    }

    @Test
    fun anotherColumnStartsItsOwnWriteAfterTheUnstartedOne() = runBlocking<Unit> {
        val read = mark(1L to true)
        val other = CustomColumnId(2, "#other")
        val second = mark(1L to false, to = other)
        assertNotEquals(read, second)
        assertEquals(setOf(TaskDependency(read, DependencyRequirement.FINISHED)), queue.get(second)!!.record.submission.dependencies.toSet())
        assertEquals(SubmissionResult.Reused(read), submitMark(2L to true))
        assertEquals(SubmissionResult.Reused(second), submitMark(2L to true, to = other))
        assertEquals(pendingOf(1L to PendingRead.Pending(true), 2L to PendingRead.Pending(true)), pending())
        // Writes run in submission order: the later one waits for the earlier to end, whatever its result.
        assertEquals(read, queue.claim(0, { emptySet() }, { true })!!.record.id)
        fail(read)
        assertEquals(second, queue.claim(0, { emptySet() }, { true })!!.record.id)
    }

    @Test
    fun aClickRacingTheStartEitherJoinsTheListOrGoesToANewWrite() = runBlocking<Unit> {
        repeat(5) { round ->
            val first = (round * 100L) + 1
            val write = mark(first to true)
            val results = coroutineScope {
                val claim = async(Dispatchers.Default) { queue.claim(0, { emptySet() }, { true }) }
                val clicks = (first + 1..first + 20).map { number -> async(Dispatchers.Default) { number to submitMark(number to true) } }
                assertEquals(write, claim.await()!!.record.id)
                clicks.awaitAll()
            }
            val fixed = queue.readStatusChanges(write)
            // Every click either joined the list it reports or went to the write after it.
            results.forEach { (number, result) ->
                val joined = result == SubmissionResult.Reused(write)
                assertEquals(joined, book(number) in fixed)
            }
            assertNotEquals(SubmissionResult.Reused(write), submitMark(first + 50 to true))
            assertEquals(fixed, queue.readStatusChanges(write))
            queue.list().filter { it.record.state !is TaskState.Finished }.forEach { finish(it.record.id) }
        }
    }

    @Test
    fun theSyncAfterAWriteRunsNextWhenItCanAndOtherwiseDoesNotBlock() = runBlocking<Unit> {
        for (online in listOf(true, false)) {
            val write = mark(1L to true)
            val earlier = created(cover(if (online) 2 else 3, TaskOrigin.USER_DOWNLOAD))
            var sync: TaskId? = null
            val executed = mutableListOf<TaskId>()
            val handler = FixtureHandler { entry, _ ->
                executed.add(entry.record.id)
                if (entry.record.id == write) {
                    // Nothing to change: the write completes after preparing and still submits its sync.
                    if (entry.stage == TaskStage.WRITE_SNAPSHOT) next(entry) else {
                        sync = created(cover(if (online) 4 else 5, TaskOrigin.USER_READ_STATUS))
                        StageOutcome.Complete(followUp = sync)
                    }
                } else StageOutcome.Complete()
            }
            TaskCoordinator(queue, listOf(handler), conditions = { record ->
                if (!online && record.id == sync) setOf(WaitingReason.NETWORK) else emptySet()
            }).drain()
            assertEquals(sync, queue.get(write)!!.followUp)
            if (online) {
                // The sync was submitted after the other user request but runs before it.
                assertEquals(listOf(write, write, sync, earlier), executed)
            } else {
                assertEquals(listOf(write, write, earlier), executed)
                assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), queue.get(sync!!)!!.record.state)
                database.readableDatabase.rawQuery("SELECT COUNT(*) FROM dispatch_next", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                TaskCoordinator(queue, listOf(handler)).drain()
                assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(sync!!)!!.record.state)
            }
        }
    }

    @Test
    fun aRejectedPushRunsWholeRoundsUpToTheRetryLimitAndCannotBeInterrupted() = runBlocking<Unit> {
        val write = mark(1L to true)
        val executed = mutableListOf<TaskStage>()
        val handler = FixtureHandler({ TaskStage.WRITE_SNAPSHOT }) { entry, _ ->
            executed.add(entry.stage)
            if (entry.stage == TaskStage.WRITE_COMMIT) {
                assertFalse(queue.control(write, TaskControl.PAUSE))
                assertFalse(queue.control(write, TaskControl.CANCEL))
                StageOutcome.Retry(TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT)))
            } else next(entry)
        }
        TaskCoordinator(queue, listOf(handler)).drain()
        val round = listOf(TaskStage.WRITE_SNAPSHOT, TaskStage.WRITE_PREPARE, TaskStage.WRITE_COMMIT)
        assertEquals(List(4) { round }.flatten(), executed)
        val failure = ((queue.get(write)!!.record.state as TaskState.Finished).result as TaskResult.Failed).failure
        assertEquals(StageFailure(TaskStage.WRITE_COMMIT, TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT))), failure)
        assertEquals(pendingOf(1L to PendingRead.Failed(true, failure.error)), pending())
        database.close()
        reopen()
        assertTrue(queue.control(write, TaskControl.RETRY))
        assertEquals(pendingOf(1L to PendingRead.Pending(true)), pending())
        executed.clear()
        TaskCoordinator(queue, listOf(handler)).drain()
        assertEquals(List(4) { round }.flatten(), executed)
    }

    @Test
    fun pendingMarksFollowTheWriteAndTheSyncAfterIt() = runBlocking<Unit> {
        val write = mark(1L to true, 2L to false)
        mark(3L to true, to = CustomColumnId(2, "#other"))
        assertEquals(pendingOf(1L to PendingRead.Pending(true), 2L to PendingRead.Pending(false)), pending())
        assertEquals(write, queue.claim(0, { emptySet() }, { true })!!.record.id)
        assertEquals(pendingOf(1L to PendingRead.Pending(true), 2L to PendingRead.Pending(false)), pending())
        // Pushed, with book 2 no longer matching: it fails while book 1 waits for the sync.
        val identityChanged = TaskError.BookIdentityChanged(book(2))
        val sync = created(cover(9, TaskOrigin.USER_READ_STATUS))
        queue.update(write, next = sync) { entry -> entry.copy(followUp = sync, record = entry.record.copy(
            state = TaskState.Finished(TaskResult.CompletedWithBookFailures(FrozenSet(listOf(BookFailure(book(2), identityChanged))))),
            controls = DurableTaskQueue.noControls)) }
        assertEquals(pendingOf(1L to PendingRead.Pending(true), 2L to PendingRead.Failed(false, identityChanged)), pending())
        // The sync ends with any result: the import decides what pages show; a successful one dismisses failures.
        queue.update(sync) { entry -> entry.copy(record = entry.record.copy(state = TaskState.Finished(TaskResult.Failed(
            StageFailure(TaskStage.COVER_TRANSFER, TaskError.Source(StorageError(StorageErrorKind.NO_NETWORK))))))) }
        assertEquals(pendingOf(2L to PendingRead.Failed(false, identityChanged)), pending())
        importSucceeded()
        assertTrue(pending().isEmpty())

        val failed = mark(4L to true, 5L to true)
        fail(failed)
        val conflict = TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT))
        assertEquals(pendingOf(4L to PendingRead.Failed(true, conflict), 5L to PendingRead.Failed(true, conflict)), pending())
        // Marking a book again replaces the failed intent for it, also for a retry of the old write.
        val again = mark(4L to false)
        assertEquals(pendingOf(4L to PendingRead.Pending(false), 5L to PendingRead.Failed(true, conflict)), pending())
        assertEquals(mapOf(book(5) to true), queue.readStatusChanges(failed))
        assertTrue(queue.control(again, TaskControl.CANCEL))
        assertEquals(pendingOf(4L to PendingRead.Failed(false, null), 5L to PendingRead.Failed(true, conflict)), pending())
        importSucceeded()
        assertTrue(pending().isEmpty())
        assertTrue(queue.control(failed, TaskControl.RETRY))
        assertEquals(pendingOf(5L to PendingRead.Pending(true)), pending())
        database.close()
        reopen()
        assertEquals(pendingOf(5L to PendingRead.Pending(true)), pending())
    }

    @Test
    fun removingAFinishedWriteRemovesItsChangeList() = runBlocking<Unit> {
        val write = mark(1L to true)
        fail(write)
        database.writableDatabase.let { db ->
            db.beginTransaction()
            try { assertEquals(listOf(write), queue.pruneFinished(db, keep = 0)); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        assertNull(queue.get(write))
        assertTrue(queue.readStatusChanges(write).isEmpty())
        assertTrue(pending().isEmpty())
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM read_status_changes", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    private fun importSucceeded() = database.writableDatabase.let { db ->
        db.beginTransaction()
        try { DurableTaskQueue.dismissReadFailures(db, identity.id); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    @Test
    fun handlerWaitPreservesLoginReasonWhenDrainReturns() = runBlocking<Unit> {
        val task = created(cover(1, TaskOrigin.USER_DOWNLOAD))
        var calls = 0
        TaskCoordinator(queue, listOf(FixtureHandler { _, _ ->
            calls++
            StageOutcome.Wait(WaitingReason.LOGIN)
        })).drain()
        assertEquals(1, calls)
        assertEquals(FrozenSet(listOf(WaitingReason.LOGIN)), (queue.get(task)!!.record.state as TaskState.Waiting).reasons)
    }

    @Test
    fun additionListingRunsWithoutCurrentLibraryAndARestartedAdditionDeactivatesIt() = runBlocking<Unit> {
        database.close()
        assertTrue(context.deleteDatabase(name))
        reopen()
        assertNull(state.current())
        val addition = state.beginAddition(BackendKind.ONEDRIVE, UUID.randomUUID()).first
        val listing = requireNotNull(addition.context)
        val task = created(TaskSubmission(TaskRequest.CandidateConfiguration(listing, "onedrive_browse"), TaskOrigin.MANUAL_SYNC))
        database.readableDatabase.rawQuery("SELECT scope_library_id FROM queued_tasks WHERE task_id = ?",
            arrayOf(task.value.toString())).use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)) }
        assertEquals(task, queue.claim(0, { emptySet() }, { true })!!.record.id)
        queue.update(task) { entry -> entry.copy(record = entry.record.copy(state = TaskState.Queued)) }
        database.close()
        reopen()
        assertEquals(addition, state.addition())
        // Only a listing belongs to an addition; a sync needs a current library.
        assertTrue(queue.submit(TaskSubmission(TaskRequest.CandidateConfiguration(listing,
            TaskRequest.CandidateConfiguration.LIBRARY_SYNC), TaskOrigin.MANUAL_SYNC)) is SubmissionResult.Rejected)
        val root = LibraryLocation.OneDrive("fixture-account", "fixture-drive", "fixture-root")
        val restarted = state.beginAddition(BackendKind.ONEDRIVE, UUID.randomUUID()).first
        assertNotEquals(addition.token, restarted.token)
        assertTrue(state.chooseAddition(addition.token, root, null, null).isFailure)
        assertNull(state.addition()!!.location)
        assertNull(state.current())
        assertNull(queue.claim(0, { emptySet() }, { true }))
        assertTrue(WaitingReason.INACTIVE_LIBRARY in (queue.get(task)!!.record.state as TaskState.Waiting).reasons)
        assertTrue(queue.submit(TaskSubmission(TaskRequest.CandidateConfiguration(listing, "onedrive_browse", "other"),
            TaskOrigin.MANUAL_SYNC)) is SubmissionResult.Rejected)
    }

    @Test
    fun cancellingAListingKeepsTheAdditionAndTheCurrentLibrary() = runBlocking<Unit> {
        val selected = state.current()
        val addition = state.beginAddition(BackendKind.ONEDRIVE, UUID.randomUUID()).first
        val submission = TaskSubmission(TaskRequest.CandidateConfiguration(requireNotNull(addition.context), "onedrive_browse"),
            TaskOrigin.MANUAL_SYNC)
        val task = created(submission)
        assertTrue(queue.control(task, TaskControl.CANCEL))
        assertEquals(addition, state.addition())
        assertEquals(selected, state.current())
        assertNull(queue.get(task)!!.record.libraryId)
        assertTrue(queue.submit(submission) is SubmissionResult.Created)
    }

    @Test
    fun aLowTaskYieldsOnceToAQueuedUserRequestThatThenWaits() = runBlocking<Unit> {
        val low = created(cover(1, TaskOrigin.VISIBLE_COVER))
        var high: TaskId? = null
        var runs = 0
        val handler = FixtureHandler { entry, execution ->
            if (entry.record.id == low) {
                runs++
                if (high == null) high = created(sync(TaskOrigin.MANUAL_SYNC))
                repeat(3) { execution.checkControl() }
            }
            StageOutcome.Complete()
        }
        // The user request is queued but cannot run yet: the low task yields to it once, then finishes.
        TaskCoordinator(queue, listOf(handler), conditions = { record ->
            if (record.scheduling.priority == TaskPriority.HIGH) setOf(WaitingReason.NETWORK) else emptySet()
        }).drain()
        assertEquals(2, runs)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(low)!!.record.state)
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), queue.get(high!!)!!.record.state)
    }

    /** [recoverTo] chooses the stage a recovery continues from; by default the saved one. */
    private class FixtureHandler(val recoverTo: (QueueEntry) -> TaskStage = { it.stage },
        val body: suspend (QueueEntry, TaskExecution) -> StageOutcome) : TaskHandler {
        override fun supports(request: TaskRequest) = true
        override fun controls(stage: TaskStage) = TaskControls(stage != TaskStage.WRITE_COMMIT, stage != TaskStage.WRITE_COMMIT, false, false)
        override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(recoverTo(entry), entry.checkpoint)
        override suspend fun execute(entry: QueueEntry, execution: TaskExecution) = body(entry, execution)
    }

    private fun next(entry: QueueEntry): StageOutcome = when (entry.stage) {
        TaskStage.COVER_TRANSFER -> StageOutcome.Advance(TaskStage.COVER_PUBLISH)
        TaskStage.FORMAT_TRANSFER -> StageOutcome.Advance(TaskStage.FORMAT_PUBLISH)
        TaskStage.WRITE_SNAPSHOT -> StageOutcome.Advance(TaskStage.WRITE_PREPARE)
        TaskStage.WRITE_PREPARE -> StageOutcome.Advance(TaskStage.WRITE_COMMIT)
        else -> StageOutcome.Complete()
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, name)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
    }

    private fun book(number: Long) = BookKey(identity.id, number, UUID.nameUUIDFromBytes("fixture-$number".toByteArray()))
    /** A stand-in for any deduplicated request, such as a sync; one fixed resource. */
    private fun sync(origin: TaskOrigin) = TaskSubmission(TaskRequest.FormatCheck(CopyKey(book(99), BookFormat.parse("EPUB"))), origin)
    private fun cover(number: Long, origin: TaskOrigin) = TaskSubmission(TaskRequest.CoverLoad(book(number)), origin)
    private fun copy(number: Long) = TaskSubmission(TaskRequest.FormatCopy(FormatResource(book(number), BookFormat.parse("EPUB"),
        SourceFileLocator.Local("fixture-$number"))), TaskOrigin.USER_DOWNLOAD)
    private val column = CustomColumnId(1, "#finished")
    /** One click marking the numbered books with their targets, in [to] (default: the read column). */
    private suspend fun submitMark(vararg changes: Pair<Long, Boolean>, to: CustomColumnId = column) =
        queue.submitReadStatus(identity.id, to, changes.associate { (number, target) -> book(number) to target }, TaskOrigin.USER_READ_STATUS)
    private suspend fun mark(vararg changes: Pair<Long, Boolean>, to: CustomColumnId = column) =
        (submitMark(*changes, to = to) as SubmissionResult.Created).taskId
    private suspend fun pending() = queue.pendingReadStatus(identity.id, column)
    private fun pendingOf(vararg shown: Pair<Long, PendingRead>) = shown.associate { (number, mark) -> book(number) to mark }
    private suspend fun fail(id: TaskId, error: TaskError = TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT))) {
        queue.update(id) { entry -> entry.copy(record = entry.record.copy(state = TaskState.Finished(TaskResult.Failed(
            StageFailure(TaskStage.WRITE_COMMIT, error))), controls = TaskControls(false, false, false, true))) }
    }
    private suspend fun created(submission: TaskSubmission) = (queue.submit(submission) as SubmissionResult.Created).taskId
    private suspend fun finish(id: TaskId) {
        queue.update(id, action = { entry -> entry.copy(record = entry.record.copy(state = TaskState.Finished(TaskResult.Completed), controls = TaskControls(false, false, false, false))) })
    }
}
