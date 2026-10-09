package io.github.chenxiex.calibrecloud.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.ApplicationDependencies
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.files.BookUris
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.state.LastOpened
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.storage.api.CopyReadResult
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskEvent
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** What a local copy check found. */
sealed interface LocatedCopy {
    data class Available(val copy: DownloadedCopy) : LocatedCopy
    data object Missing : LocatedCopy
    data class Failed(val error: StorageError) : LocatedCopy
}

/** Storage, queue and record access of the open flow; replaceable in tests. */
interface BookOpening {
    suspend fun selection(): LibrarySelection?

    /** Checks the complete copy through the ordinary copy reader; never contacts the source. */
    suspend fun locate(key: CopyKey): LocatedCopy

    /** Submits (or promotes) the user download of one format; null when the current import rejects it. */
    suspend fun download(key: CopyKey, selectionToken: UUID): TaskId?

    /** Whether a complete import exists, to explain a rejected download. */
    suspend fun metadataAvailable(): Boolean

    fun observe(task: TaskId): Flow<TaskState>

    /** Every unfinished copy download of any origin, emitted whole after each change; none by default. */
    fun downloads(): Flow<List<ActiveDownload>> = emptyFlow()

    suspend fun wake()

    /** Cancels the download task itself, not only the wait for it. */
    suspend fun cancel(task: TaskId)

    suspend fun lastOpened(libraryId: LibraryId): LastOpened?

    suspend fun saveLastOpened(value: LastOpened)
}

/** Why a book could not be handed to a reader. Pages resolve the text from resources. */
enum class OpenProblem {
    NO_FORMAT, NO_APP, LAUNCH_FAILED, COPY_UNREADABLE, NO_METADATA, UNAVAILABLE, DOWNLOAD_FAILED,
}

/** The state of the book the user waits for, or why it could not be opened; the text of its notice. */
sealed interface OpenStatus {
    val title: String
    val format: BookFormat?
    val backend: BackendKind

    /** [task] is null until the queue reports the submitted download. */
    data class Downloading(override val title: String, override val format: BookFormat, override val backend: BackendKind,
        val task: TaskState?) : OpenStatus

    /** [key] is the format that failed; null when the book has none. */
    data class Failed(override val title: String, override val format: BookFormat?, override val backend: BackendKind,
        val problem: OpenProblem, val key: CopyKey?, val task: TaskState? = null) : OpenStatus
}

/**
 * Whether [OpenStatus] needs the user: a failure, or a download held by a wait condition or a pause.
 * Such a status shows a warning mark and is explained by a system notification.
 */
fun OpenStatus.needsUser() = when (this) {
    is OpenStatus.Failed -> true
    is OpenStatus.Downloading -> task is TaskState.Waiting || task is TaskState.Paused
}

/**
 * The mark drawn where [book]'s download check would be. While a download runs it is cancellable
 * and [fraction] is the completed share in 0..1 when the task reports a total (null while queued or
 * when the total is unknown); [warning] replaces the cross with an exclamation mark when the status
 * needs the user. A failure is a warning without a download.
 */
data class OpenMark(val book: BookKey, val fraction: Float?, val warning: Boolean, val cancellable: Boolean)

/** An unfinished copy download task of [book], whoever submitted it. */
data class ActiveDownload(val task: TaskId, val book: BookKey, val state: TaskState)

/**
 * A book's unfinished download, drawn like a cancellable [OpenMark] without a warning: [fraction] is
 * the completed share when the running task reports a total, kept while it waits, otherwise null.
 */
data class DownloadingMark(val task: TaskId, val fraction: Float?)

/** A notification to post for [status], or to withdraw the last one when [status] is null. */
data class OpenNotice(val id: Long, val status: OpenStatus?)

/** One request to hand a complete copy to a reader; the screen starts it and reports [LaunchOutcome]. */
data class OpenLaunch(val id: Long, val copy: DownloadedCopy)

enum class LaunchOutcome { STARTED, NO_APP, FAILED }

/**
 * Opening a book in an external reader (R28) and the last opened record (R29). At most one intent
 * exists: the latest single-format request of this Activity. It is revoked by a newer request, by
 * leaving the page, by the app going to the background and by a library switch; a revoked intent's
 * download keeps running in the queue but never opens anything. Only a still current intent, while
 * the app is in the foreground and the selection is unchanged, becomes an [OpenLaunch]. The last
 * opened record changes only after the system accepted the launch. [mark] shows the intent on its
 * book: download progress, changed at most every [PROGRESS_INTERVAL_MS] ([now] is the monotonic
 * clock in milliseconds), or a warning. [cancelDownload] cancels the task and the intent together.
 * Each new condition that needs the user becomes one [notice], which the screen posts and confirms.
 * Independently of any intent, [downloads] marks every book with an unfinished download task, with
 * the same progress interval, so a download keeps its mark after its intent is revoked.
 */
class OpenViewModel(
    private val port: BookOpening,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {
    var status by mutableStateOf<OpenStatus?>(null)
        private set
    var mark by mutableStateOf<OpenMark?>(null)
        private set
    var notice by mutableStateOf<OpenNotice?>(null)
        private set
    var launch by mutableStateOf<OpenLaunch?>(null)
        private set
    var lastOpened by mutableStateOf<LastOpened?>(null)
        private set
    var downloads by mutableStateOf<Map<BookKey, DownloadingMark>>(emptyMap())
        private set

    private class Intent(val id: Long, val key: CopyKey, val title: String, val selectionToken: UUID, val backend: BackendKind) {
        var task: TaskId? = null
        /** Cancel was pressed before the queue returned the task. */
        var cancelled = false
    }

    private var foreground = true
    private var serial = 0L
    private var intent: Intent? = null
    private var work: Job? = null
    private var progressShownAt = 0L
    private var progressUpdate: Job? = null
    private var noticeSerial = 0L
    /** What the last notice was about, so a repeated wait does not notify again. */
    private var noticed: Any? = null
    private var downloadsShownAt = 0L
    private var downloadsUpdate: Job? = null

    init {
        viewModelScope.launch {
            try {
                port.downloads().collect { showDownloads(it) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Marks of downloads stay as last shown; opening and its own mark are unaffected.
            }
        }
    }

    /** Opens [key], downloading it first when no complete copy exists. Replaces any earlier intent. */
    fun open(key: CopyKey, title: String) {
        revoke()
        // A new attempt reports its conditions afresh, even when they repeat.
        noticed = null
        val id = ++serial
        work = viewModelScope.launch {
            val selected = port.selection()
            if (selected?.identity?.id != key.book.libraryId || serial != id) return@launch
            val current = Intent(id, key, title, selected.token, selected.backend)
            intent = current
            try {
                when (val located = port.locate(key)) {
                    is LocatedCopy.Available -> deliver(current, located.copy)
                    is LocatedCopy.Failed -> fail(current, OpenProblem.COPY_UNREADABLE)
                    LocatedCopy.Missing -> download(current)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (isCurrent(current)) fail(current, OpenProblem.COPY_UNREADABLE)
            }
        }
    }

    /** Opens the recorded format of the last opened book by the same rules. */
    fun openLastOpened() {
        val value = lastOpened ?: return
        open(value.key, value.title)
    }

    /** A book without any usable format: nothing is submitted. */
    fun failNoFormat(book: BookKey, title: String) {
        revoke()
        val id = serial
        work = viewModelScope.launch {
            val backend = port.selection()?.backend ?: return@launch
            if (serial == id) show(OpenStatus.Failed(title, null, backend, OpenProblem.NO_FORMAT, null), book)
        }
    }

    /** Leaving the page, a new click or a library switch: the download continues but never opens. */
    fun revoke() {
        work?.cancel()
        work = null
        intent = null
        launch = null
        status = null
        clearMark()
    }

    /** Cancels the waited for download task and the intent; a task not yet returned is cancelled once it is. */
    fun cancelDownload() {
        val current = intent ?: return
        if (mark?.cancellable != true) return
        val task = current.task
        withdrawNotice()
        if (task == null) {
            current.cancelled = true
            clearMark()
            return
        }
        revoke()
        viewModelScope.launch { cancel(task) }
    }

    /** Cancels [task] from its book's mark: the intent's own download goes through [cancelDownload]. */
    fun cancelTask(task: TaskId) {
        if (intent?.task == task) return cancelDownload()
        downloads = downloads.filterValues { it.task != task }
        viewModelScope.launch { cancel(task) }
    }

    /** The screen posted (or withdrew) [id]. */
    fun noticeHandled(id: Long) {
        if (notice?.id == id) notice = null
    }

    /** The app went to (or returned from) the background; configuration changes are not reported. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) revoke()
    }

    /** Called by the screen after it tried to start [started]. */
    fun launched(started: OpenLaunch, outcome: LaunchOutcome) {
        if (launch?.id != started.id) return
        launch = null
        val current = intent?.takeIf { it.id == started.id } ?: return
        intent = null
        when (outcome) {
            LaunchOutcome.STARTED -> {
                status = null
                withdrawNotice()
                val value = LastOpened(current.key, current.title)
                lastOpened = value
                viewModelScope.launch {
                    try {
                        port.saveLastOpened(value)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        // The reader is open; the record is retried on the next successful open.
                    }
                }
            }
            LaunchOutcome.NO_APP -> show(OpenStatus.Failed(current.title, current.key.format, current.backend, OpenProblem.NO_APP, current.key), current.key.book)
            LaunchOutcome.FAILED ->
                show(OpenStatus.Failed(current.title, current.key.format, current.backend, OpenProblem.LAUNCH_FAILED, current.key), current.key.book)
        }
    }

    /** Rereads the current library's record; an intent of another selection is revoked. */
    fun refresh() {
        viewModelScope.launch {
            try {
                val selected = port.selection()
                if (intent != null && intent?.selectionToken != selected?.token) revoke()
                val library = selected?.identity?.id
                val value = library?.let { port.lastOpened(it) }
                if (port.selection()?.token != selected?.token) return@launch
                lastOpened = value
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // The previous record stays until the next refresh.
            }
        }
    }

    private suspend fun download(current: Intent) {
        show(OpenStatus.Downloading(current.title, current.key.format, current.backend, null), current.key.book)
        progressShownAt = now()
        val task = port.download(current.key, current.selectionToken)
        if (!isCurrent(current)) return
        if (task == null) {
            fail(current, if (port.metadataAvailable()) OpenProblem.UNAVAILABLE else OpenProblem.NO_METADATA)
            return
        }
        current.task = task
        if (current.cancelled) {
            revoke()
            cancel(task)
            return
        }
        try { port.wake() } catch (failure: CancellationException) { throw failure } catch (_: Exception) { }
        val state = port.observe(task).first { state ->
            if (isCurrent(current) && state !is TaskState.Finished) {
                if (!current.cancelled) {
                    show(OpenStatus.Downloading(current.title, current.key.format, current.backend, state), current.key.book)
                    showProgress(current, state)
                }
            }
            state is TaskState.Finished
        } as TaskState.Finished
        if (!isCurrent(current)) return
        clearMark()
        if (state.result != TaskResult.Completed) {
            intent = null
            show(OpenStatus.Failed(current.title, current.key.format, current.backend, OpenProblem.DOWNLOAD_FAILED, current.key, state), current.key.book)
            return
        }
        when (val located = port.locate(current.key)) {
            is LocatedCopy.Available -> deliver(current, located.copy)
            else -> fail(current, OpenProblem.COPY_UNREADABLE)
        }
    }

    /**
     * Running tasks with a total move the ring; other states keep the last share. A change sooner than
     * [PROGRESS_INTERVAL_MS] after the last shown one is held back and shown when the interval ends.
     */
    private fun showProgress(current: Intent, state: TaskState) {
        val shown = mark ?: return
        val running = state as? TaskState.Running ?: return
        val fraction = running.progress?.let { progress ->
            progress.total?.takeIf { it > 0 }?.let { (progress.completed.toFloat() / it).coerceIn(0f, 1f) }
        }
        if (fraction == shown.fraction) return
        progressUpdate?.cancel()
        val wait = progressShownAt + PROGRESS_INTERVAL_MS - now()
        if (wait <= 0) {
            mark = shown.copy(fraction = fraction)
            progressShownAt = now()
            return
        }
        progressUpdate = viewModelScope.launch {
            delay(wait)
            val latest = mark
            if (!isCurrent(current) || latest?.cancellable != true) return@launch
            mark = latest.copy(fraction = fraction)
            progressShownAt = now()
        }
    }

    /**
     * One mark per book, preferring a running task. A task that appears or ends shows at once; a
     * change of share alone waits for [PROGRESS_INTERVAL_MS] after the last shown change.
     */
    private fun showDownloads(active: List<ActiveDownload>) {
        val shown = downloads
        val next = active.groupBy { it.book }.mapValues { (book, tasks) ->
            val chosen = tasks.firstOrNull { it.state is TaskState.Running } ?: tasks.first()
            val running = chosen.state as? TaskState.Running
            val fraction = if (running != null) running.progress?.let { progress ->
                progress.total?.takeIf { it > 0 }?.let { (progress.completed.toFloat() / it).coerceIn(0f, 1f) }
            } else shown[book]?.takeIf { it.task == chosen.task }?.fraction
            DownloadingMark(chosen.task, fraction)
        }
        if (next == shown) return
        downloadsUpdate?.cancel()
        val sameTasks = next.mapValues { it.value.task } == shown.mapValues { it.value.task }
        val wait = downloadsShownAt + PROGRESS_INTERVAL_MS - now()
        if (!sameTasks || wait <= 0) {
            downloads = next
            downloadsShownAt = now()
            return
        }
        downloadsUpdate = viewModelScope.launch {
            delay(wait)
            downloads = next
            downloadsShownAt = now()
        }
    }

    /**
     * Shows [next] on [book]: a download keeps its shown share, a failure drops it. A status that needs
     * the user, about something not yet noticed, becomes a new notice.
     */
    private fun show(next: OpenStatus, book: BookKey) {
        status = next
        val downloading = next is OpenStatus.Downloading
        if (!downloading) clearMark()
        mark = OpenMark(book, mark?.fraction?.takeIf { downloading }, next.needsUser(), downloading)
        val subject = subject(next)
        if (next.needsUser() && subject != noticed) notice = OpenNotice(++noticeSerial, next)
        noticed = subject
    }

    /** What a notice is about: the whole failure, or the reasons a download waits. */
    private fun subject(value: OpenStatus): Any? = when (value) {
        is OpenStatus.Failed -> value
        is OpenStatus.Downloading -> when (val task = value.task) {
            is TaskState.Waiting -> task.reasons
            is TaskState.Paused -> TaskState.Paused::class
            else -> null
        }
    }

    private fun withdrawNotice() {
        noticed = null
        notice = OpenNotice(++noticeSerial, null)
    }

    private fun clearMark() {
        progressUpdate?.cancel()
        progressUpdate = null
        mark = null
    }

    private suspend fun cancel(task: TaskId) {
        try {
            port.cancel(task)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // The task page still offers cancel; the intent is gone either way.
        }
    }

    private suspend fun deliver(current: Intent, copy: DownloadedCopy) {
        if (!isCurrent(current)) return
        // Only a still waiting user of the same library in the foreground gets the reader.
        if (!foreground || port.selection()?.token != current.selectionToken) {
            revoke()
            return
        }
        launch = OpenLaunch(current.id, copy)
    }

    private fun fail(current: Intent, problem: OpenProblem) {
        if (!isCurrent(current)) return
        intent = null
        show(OpenStatus.Failed(current.title, current.key.format, current.backend, problem, current.key), current.key.book)
    }

    private fun isCurrent(current: Intent) = intent?.id == current.id

    companion object {
        /** Longest pause between progress changes on the e-ink screen (R20, as on the task page). */
        const val PROGRESS_INTERVAL_MS = 2_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(OpenViewModel(QueueBookOpening(dependencies)))!!
            }
        }
    }
}

/** Production port: the shared copy reader, user downloads through the copy service, and the state database. */
class QueueBookOpening(private val dependencies: ApplicationDependencies) : BookOpening {
    override suspend fun selection() = dependencies.state.current()

    override suspend fun locate(key: CopyKey): LocatedCopy = when (val read = dependencies.copyReader.read(key)) {
        is CopyReadResult.Available -> {
            withContext(Dispatchers.IO + NonCancellable) { read.handle.close() }
            dependencies.state.find(key)?.let { LocatedCopy.Available(it) } ?: LocatedCopy.Missing
        }
        CopyReadResult.Missing -> LocatedCopy.Missing
        is CopyReadResult.Failed -> LocatedCopy.Failed(read.error)
    }

    override suspend fun download(key: CopyKey, selectionToken: UUID): TaskId? =
        when (val result = dependencies.copyService.submit(key, selectionToken)) {
            is SubmissionResult.Created -> result.taskId
            is SubmissionResult.Reused -> result.taskId
            is SubmissionResult.Promoted -> result.taskId
            is SubmissionResult.Rejected -> null
        }

    override suspend fun metadataAvailable() = dependencies.libraryQuery.overview() != null

    override fun observe(task: TaskId) = dependencies.taskQueue.observe(task).map { it.state }

    override fun downloads(): Flow<List<ActiveDownload>> = channelFlow {
        val records = mutableMapOf<TaskId, TaskRecord>()
        val lock = Mutex()
        suspend fun publish() = send(records.values.mapNotNull { record ->
            (record.submission.request as? TaskRequest.FormatCopy)?.let { ActiveDownload(record.id, it.resource.book, record.state) }
        })
        // Subscribe before listing so no change between the two is lost; a listed record never
        // replaces a newer event, and finished tasks leave the set.
        launch(start = CoroutineStart.UNDISPATCHED) {
            dependencies.taskQueue.events.collect { event ->
                val record = (event as? TaskEvent.Changed)?.record ?: return@collect
                if (record.submission.request !is TaskRequest.FormatCopy) return@collect
                lock.withLock {
                    if (record.state is TaskState.Finished) records.remove(record.id) else records[record.id] = record
                    publish()
                }
            }
        }
        lock.withLock {
            dependencies.taskQueue.list().map { it.record }.filter {
                it.submission.request is TaskRequest.FormatCopy && it.state !is TaskState.Finished
            }.forEach { records.putIfAbsent(it.id, it) }
            publish()
        }
    }

    override suspend fun wake() = dependencies.taskCoordinator.requestRun()

    override suspend fun cancel(task: TaskId) {
        dependencies.taskQueue.control(task, TaskControl.CANCEL)
    }

    override suspend fun lastOpened(libraryId: LibraryId) = dependencies.lastOpened.get(libraryId)

    override suspend fun saveLastOpened(value: LastOpened) = dependencies.lastOpened.save(value)
}

/** Hands [copy] to the system's reader chooser for its MIME type with a read-only grant. */
fun launchReader(context: Context, copy: DownloadedCopy): LaunchOutcome = try {
    context.startActivity(BookUris.viewIntent(context, copy))
    LaunchOutcome.STARTED
} catch (_: ActivityNotFoundException) {
    LaunchOutcome.NO_APP
} catch (_: RuntimeException) {
    LaunchOutcome.FAILED
}
