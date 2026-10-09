package io.github.chenxiex.calibrecloud.ui

import android.graphics.Bitmap
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.LibraryIdentity
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.LastOpened
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.CommitState
import io.github.chenxiex.calibrecloud.tasks.api.FrozenSet
import io.github.chenxiex.calibrecloud.tasks.api.StageFailure
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskProgress
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskStage
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OpenViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraryId = LibraryId(UUID.randomUUID())
    private val identity = LibraryIdentity(libraryId, LibraryLocation.Local("a.documents", "tree"), UUID.randomUUID())
    private val epub = BookFormat.parse("EPUB")
    private val first = CopyKey(BookKey(libraryId, 1, UUID(0, 1)), epub)
    private val second = CopyKey(BookKey(libraryId, 2, UUID(0, 2)), epub)

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)
    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    /** Copies appear when their task completes; downloads stay queued until a test finishes them. */
    private inner class FakeOpening : BookOpening {
        var selected: LibrarySelection? = LibrarySelection(UUID.randomUUID(), identity.location, identity, BackendKind.ONEDRIVE)
        val copies = mutableMapOf<CopyKey, DownloadedCopy>()
        val tasks = mutableMapOf<CopyKey, MutableStateFlow<TaskState>>()
        val records = mutableMapOf<LibraryId, LastOpened>()
        var accepts = true
        var metadata = true
        var submitted = 0
        val cancelled = mutableListOf<TaskId>()
        val active = MutableStateFlow<List<ActiveDownload>>(emptyList())
        /** Holds submissions back until completed, when set. */
        var submission: CompletableDeferred<Unit>? = null

        override suspend fun selection() = selected
        override suspend fun locate(key: CopyKey) = copies[key]?.let { LocatedCopy.Available(it) } ?: LocatedCopy.Missing
        override suspend fun download(key: CopyKey, selectionToken: UUID): TaskId? {
            if (!accepts) return null
            submission?.await()
            submitted++
            tasks.getOrPut(key) { MutableStateFlow(TaskState.Queued) }
            return TaskId(UUID(1, key.book.sourceId))
        }
        override suspend fun metadataAvailable() = metadata
        override fun observe(task: TaskId) = tasks.entries.first { it.key.book.sourceId == task.value.leastSignificantBits }.value
        override suspend fun wake() {}
        override fun downloads() = active
        override suspend fun cancel(task: TaskId) { cancelled += task }
        override suspend fun lastOpened(libraryId: LibraryId) = records[libraryId]
        override suspend fun saveLastOpened(value: LastOpened) { records[value.key.book.libraryId] = value }
        override suspend fun cover(value: LastOpened): Bitmap? = null

        fun complete(key: CopyKey) {
            copies[key] = copyOf(key)
            tasks.getValue(key).value = TaskState.Finished(TaskResult.Completed)
        }
    }

    private fun copyOf(key: CopyKey) = DownloadedCopy(key, CompleteCopyLocation(libraryId, UUID.randomUUID()), "Book ${key.book.sourceId}",
        10, FileVersion(BackendKind.ONEDRIVE, "v"), SourceAvailability.AVAILABLE)

    private val port = FakeOpening()

    private fun TestScope.model() = OpenViewModel(port) { testScheduler.currentTime }.also { advanceUntilIdle() }

    @Test
    fun aCompleteCopyOpensAtOnceAndOnlyAStartedLaunchBecomesTheLastOpened() = runTest(dispatcher) {
        port.copies[first] = copyOf(first)
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        val launch = model.launch!!
        assertEquals(first, launch.copy.key)
        assertNull(model.status)
        assertNull(model.lastOpened)
        assertEquals(0, port.submitted)

        model.launched(launch, LaunchOutcome.STARTED)
        advanceUntilIdle()
        assertNull(model.launch)
        assertEquals(LastOpened(first, "First"), model.lastOpened)
        assertEquals(LastOpened(first, "First"), port.records[libraryId])
    }

    @Test
    fun aMissingCopyIsDownloadedWithVisibleStatesAndOpensWhileStillWaitingInTheForeground() = runTest(dispatcher) {
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        assertEquals(1, port.submitted)
        assertEquals(TaskState.Queued, (model.status as OpenStatus.Downloading).task)
        val waiting = TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK)))
        port.tasks.getValue(first).value = waiting
        advanceUntilIdle()
        assertEquals(waiting, (model.status as OpenStatus.Downloading).task)
        val running = TaskState.Running(TaskStage.FORMAT_TRANSFER, TaskProgress(5, 10))
        port.tasks.getValue(first).value = running
        advanceUntilIdle()
        assertEquals(running, (model.status as OpenStatus.Downloading).task)
        assertEquals(OpenMark(first.book, 0.5f, warning = false, cancellable = true), model.mark)
        assertNull(model.launch)

        port.complete(first)
        advanceUntilIdle()
        assertNull(model.mark)
        assertEquals(first, model.launch!!.copy.key)
    }

    @Test
    fun progressChangesAtMostEveryTwoSecondsAndTheLatestShareIsShownLate() = runTest(dispatcher) {
        val model = model()
        model.open(first, "First")
        runCurrent()
        assertEquals(OpenMark(first.book, null, warning = false, cancellable = true), model.mark)
        val task = port.tasks.getValue(first)
        advanceTimeBy(OpenViewModel.PROGRESS_INTERVAL_MS)
        runCurrent()
        task.value = TaskState.Running(TaskStage.FORMAT_TRANSFER, TaskProgress(1, 10))
        runCurrent()
        assertEquals(0.1f, model.mark!!.fraction)
        task.value = TaskState.Running(TaskStage.FORMAT_TRANSFER, TaskProgress(2, 10))
        runCurrent()
        task.value = TaskState.Running(TaskStage.FORMAT_TRANSFER, TaskProgress(3, 10))
        advanceTimeBy(OpenViewModel.PROGRESS_INTERVAL_MS - 1)
        assertEquals(0.1f, model.mark!!.fraction)
        advanceTimeBy(2)
        assertEquals(0.3f, model.mark!!.fraction)
        // A wait without progress keeps the last share and warns.
        task.value = TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK)))
        advanceUntilIdle()
        assertEquals(OpenMark(first.book, 0.3f, warning = true, cancellable = true), model.mark)
    }

    @Test
    fun everyUnfinishedDownloadIsMarkedWithoutAnIntentAndCanBeCancelled() = runTest(dispatcher) {
        val model = model()
        val batch = TaskId(UUID(2, 1))
        val book = first.book
        port.active.value = listOf(ActiveDownload(batch, book, TaskState.Queued))
        runCurrent()
        assertEquals(mapOf(book to DownloadingMark(batch, null)), model.downloads)
        assertNull(model.mark)
        // A share alone waits for the interval; a waiting task keeps the last share.
        port.active.value = listOf(ActiveDownload(batch, book, TaskState.Running(TaskStage.FORMAT_TRANSFER, TaskProgress(1, 4))))
        runCurrent()
        assertEquals(DownloadingMark(batch, null), model.downloads[book])
        advanceTimeBy(OpenViewModel.PROGRESS_INTERVAL_MS)
        runCurrent()
        assertEquals(DownloadingMark(batch, 0.25f), model.downloads[book])
        port.active.value = listOf(ActiveDownload(batch, book, TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK)))))
        advanceUntilIdle()
        assertEquals(DownloadingMark(batch, 0.25f), model.downloads[book])
        model.cancelTask(batch)
        advanceUntilIdle()
        assertEquals(listOf(batch), port.cancelled)
        assertTrue(model.downloads.isEmpty())
        // An open revoked by the background leaves the download marked through the queue.
        model.open(second, "Second")
        runCurrent()
        val task = TaskId(UUID(1, second.book.sourceId))
        port.active.value = listOf(ActiveDownload(task, second.book, TaskState.Queued))
        model.setForeground(false)
        advanceUntilIdle()
        assertNull(model.mark)
        assertEquals(DownloadingMark(task, null), model.downloads[second.book])
        // An ended task leaves at once.
        port.active.value = emptyList()
        runCurrent()
        assertTrue(model.downloads.isEmpty())
    }

    @Test
    fun cancellingCancelsTheTaskAndTheOpen() = runTest(dispatcher) {
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        model.cancelDownload()
        advanceUntilIdle()
        assertEquals(listOf(TaskId(UUID(1, 1))), port.cancelled)
        assertNull(model.mark)
        assertNull(model.status)
        port.complete(first)
        advanceUntilIdle()
        assertNull(model.launch)

        // Cancel pressed before the queue returned the task still reaches it.
        port.submission = CompletableDeferred()
        model.open(second, "Second")
        runCurrent()
        assertEquals(OpenMark(second.book, null, warning = false, cancellable = true), model.mark)
        model.cancelDownload()
        assertNull(model.mark)
        port.submission!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(TaskId(UUID(1, 2)), port.cancelled.last())
        assertNull(model.mark)
        assertNull(model.status)
    }

    @Test
    fun backgroundLeavingAndLibrarySwitchOnlyLetTheDownloadFinish() = runTest(dispatcher) {
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        model.setForeground(false)
        assertNull(model.status)
        port.complete(first)
        advanceUntilIdle()
        model.setForeground(true)
        advanceUntilIdle()
        assertNull(model.launch)

        model.open(second, "Second")
        advanceUntilIdle()
        model.revoke()
        port.complete(second)
        advanceUntilIdle()
        assertNull(model.launch)

        val third = CopyKey(BookKey(libraryId, 3, UUID(0, 3)), epub)
        model.open(third, "Third")
        advanceUntilIdle()
        port.selected = port.selected!!.copy(token = UUID.randomUUID())
        port.complete(third)
        advanceUntilIdle()
        assertNull(model.launch)
        assertNull(model.status)
        assertNull(model.lastOpened)
    }

    @Test
    fun aNewerClickReplacesTheWaitingIntent() = runTest(dispatcher) {
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        port.copies[second] = copyOf(second)
        model.open(second, "Second")
        advanceUntilIdle()
        val launch = model.launch!!
        assertEquals(second, launch.copy.key)
        model.launched(launch, LaunchOutcome.STARTED)
        port.complete(first)
        advanceUntilIdle()
        // The first download finished after it was replaced: nothing opens and the record stays.
        assertNull(model.launch)
        assertEquals(LastOpened(second, "Second"), model.lastOpened)
    }

    @Test
    fun noReaderShowsAnErrorKeepsTheRecordAndRetryOpensAgain() = runTest(dispatcher) {
        port.copies[first] = copyOf(first)
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        model.launched(model.launch!!, LaunchOutcome.NO_APP)
        advanceUntilIdle()
        val failed = model.status as OpenStatus.Failed
        assertEquals(OpenProblem.NO_APP, failed.problem)
        assertNull(model.lastOpened)
        assertTrue(port.records.isEmpty())
        assertEquals(OpenMark(first.book, null, warning = true, cancellable = false), model.mark)
        // The copy is kept; tapping the book again hands it over without a new download.
        model.open(first, "First")
        advanceUntilIdle()
        assertEquals(first, model.launch!!.copy.key)
        assertEquals(0, port.submitted)
    }

    @Test
    fun failedOrRejectedDownloadsExplainWhy() = runTest(dispatcher) {
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        val failure = TaskState.Finished(TaskResult.Failed(StageFailure(TaskStage.FORMAT_TRANSFER,
            TaskError.Source(StorageError(StorageErrorKind.LOGIN_REQUIRED)), CommitState.NotCommitted)))
        port.tasks.getValue(first).value = failure
        advanceUntilIdle()
        val failed = model.status as OpenStatus.Failed
        assertEquals(OpenProblem.DOWNLOAD_FAILED, failed.problem)
        assertEquals(MoreTarget.ONEDRIVE_LOGIN, openMoreTarget(failed))

        port.accepts = false
        model.open(second, "Second")
        advanceUntilIdle()
        assertEquals(OpenProblem.UNAVAILABLE, (model.status as OpenStatus.Failed).problem)
        port.metadata = false
        model.open(second, "Second")
        advanceUntilIdle()
        assertEquals(OpenProblem.NO_METADATA, (model.status as OpenStatus.Failed).problem)
        assertEquals(MoreTarget.SYNC, openMoreTarget(model.status!!))

        model.failNoFormat(second.book, "Empty")
        advanceUntilIdle()
        assertEquals(OpenProblem.NO_FORMAT, (model.status as OpenStatus.Failed).problem)
        assertNull(model.launch)
    }

    @Test
    fun theLastOpenedRecordBelongsToTheCurrentLibrary() = runTest(dispatcher) {
        port.records[libraryId] = LastOpened(first, "First")
        val model = model()
        model.refresh()
        advanceUntilIdle()
        assertEquals(LastOpened(first, "First"), model.lastOpened)

        val other = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("a.documents", "other"), UUID.randomUUID())
        port.selected = LibrarySelection(UUID.randomUUID(), other.location, other, BackendKind.LOCAL)
        model.refresh()
        advanceUntilIdle()
        assertNull(model.lastOpened)
        model.openLastOpened()
        advanceUntilIdle()
        assertNull(model.launch)
        assertEquals(0, port.submitted)
    }

    @Test
    fun eachNewConditionThatNeedsTheUserBecomesOneNotice() = runTest(dispatcher) {
        val model = model()
        model.open(first, "First")
        advanceUntilIdle()
        assertNull(model.notice)
        val task = port.tasks.getValue(first)
        val network = TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK)))
        task.value = network
        advanceUntilIdle()
        val waiting = model.notice!!
        assertEquals(network, (waiting.status as OpenStatus.Downloading).task)
        model.noticeHandled(waiting.id)
        // The same wait reported again does not notify twice; a different one does.
        task.value = TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK)))
        advanceUntilIdle()
        assertNull(model.notice)
        task.value = TaskState.Running(TaskStage.FORMAT_TRANSFER, null)
        advanceUntilIdle()
        assertEquals(OpenMark(first.book, null, warning = false, cancellable = true), model.mark)
        task.value = TaskState.Waiting(FrozenSet(listOf(WaitingReason.LOGIN)))
        advanceUntilIdle()
        assertEquals(MoreTarget.ONEDRIVE_LOGIN, openMoreTarget(model.notice!!.status!!))
        model.noticeHandled(model.notice!!.id)

        // Cancelling withdraws the notification.
        model.cancelDownload()
        advanceUntilIdle()
        assertNull(model.notice!!.status)
        model.noticeHandled(model.notice!!.id)

        // A failure notifies; a started reader withdraws it.
        port.copies[second] = copyOf(second)
        model.open(second, "Second")
        advanceUntilIdle()
        model.launched(model.launch!!, LaunchOutcome.FAILED)
        assertEquals(OpenProblem.LAUNCH_FAILED, (model.notice!!.status as OpenStatus.Failed).problem)
        model.noticeHandled(model.notice!!.id)
        // Tapping the book again and failing the same way notifies again.
        model.open(second, "Second")
        advanceUntilIdle()
        model.launched(model.launch!!, LaunchOutcome.FAILED)
        assertEquals(OpenProblem.LAUNCH_FAILED, (model.notice!!.status as OpenStatus.Failed).problem)
        model.noticeHandled(model.notice!!.id)
        model.open(second, "Second")
        advanceUntilIdle()
        model.launched(model.launch!!, LaunchOutcome.STARTED)
        assertNull(model.notice!!.status)
        assertNull(model.mark)
    }
}
