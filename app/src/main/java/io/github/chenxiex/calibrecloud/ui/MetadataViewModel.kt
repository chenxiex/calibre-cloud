package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.library.formatOrder
import io.github.chenxiex.calibrecloud.library.moved
import io.github.chenxiex.calibrecloud.metadata.ImportedLibrary
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.tasks.api.TaskEvent
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The chosen library as the "更多" page describes it: its private import, the latest sync of the
 * current selection, the read column and the format order. Reads only private state; [synchronize]
 * is the one action that enqueues source work, and column or order changes never write to the source.
 */
class MetadataViewModel(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val queue: DurableTaskQueue,
    private val requestSync: suspend () -> TaskId?,
    private val wake: suspend () -> Unit,
) : ViewModel() {
    var selection by mutableStateOf<LibrarySelection?>(null)
        private set
    var imported by mutableStateOf<ImportedLibrary?>(null)
        private set
    /** The newest library sync of the current selection, whatever its origin. */
    var syncRecord by mutableStateOf<TaskRecord?>(null)
        private set
    /** The last sync request found no library to sync. */
    var syncRejected by mutableStateOf(false)
        private set
    /** Saved order followed by the imported formats it does not name. */
    var formats by mutableStateOf<List<BookFormat>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var readFailed by mutableStateOf(false)
        private set
    var configurationFailed by mutableStateOf(false)
        private set
    private val mutex = Mutex()
    private var listening: Job? = null

    /** While shown, task changes of library syncs and published imports refresh the page. */
    fun setVisible(visible: Boolean) {
        listening?.cancel()
        if (!visible) return
        restore()
        listening = viewModelScope.launch {
            queue.events.collect { event ->
                val request = when (event) {
                    is TaskEvent.Changed -> event.record.submission.request
                    is TaskEvent.CacheChanged -> event.request
                }
                if (request is TaskRequest.CandidateConfiguration) restore()
            }
        }
    }

    fun restore() {
        viewModelScope.launch { mutex.withLock { refresh() } }
    }

    /** Manual sync (R19): high priority, reusing and promoting an equivalent unfinished sync. */
    fun synchronize() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                syncRejected = requestSync() == null
                if (!syncRejected) wake()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                syncRejected = true
            } finally {
                busy = false
                restore()
            }
        }
    }

    fun selectColumn(column: CustomColumnId?) {
        val displayed = imported ?: return
        configure { metadata.selectReadColumn(displayed, column) }
    }

    /** Moves [format] one place and saves the whole shown order. */
    fun moveFormat(format: BookFormat, up: Boolean) {
        val order = formats.moved(format, up)
        if (order == formats) return
        configure { state.setFormatPriority(order); true }
    }

    private fun configure(change: suspend () -> Boolean) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                mutex.withLock {
                    configurationFailed = !change()
                    refresh()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                configurationFailed = true
            } finally {
                busy = false
            }
        }
    }

    private suspend fun refresh() {
        try {
            val before = state.current()
            val updated = metadata.currentImport()
            val stored = state.formatPriority()
            val sync = before?.token?.let { queue.latestLibrarySync(it) }
            val after = state.current()
            // A switched selection must not receive a read begun for its predecessor.
            if (before?.token != after?.token) return
            selection = after
            imported = updated.takeIf { it?.identity == after?.identity }
            syncRecord = sync
            formats = formatOrder(stored, imported?.metadata?.books.orEmpty().flatMap { book -> book.formats.map { it.format } })
            readFailed = false
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            selection = null
            imported = null
            syncRecord = null
            readFailed = true
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(MetadataViewModel(dependencies.state, dependencies.metadata, dependencies.taskQueue,
                    { dependencies.librarySync.request(TaskOrigin.MANUAL_SYNC) }, dependencies.taskCoordinator::requestRun))!!
            }
        }
    }
}

/** The newest library sync of the selection with [token], whatever its origin and state. */
internal suspend fun DurableTaskQueue.latestLibrarySync(token: UUID): TaskRecord? = list().map { it.record }.filter {
    val request = it.submission.request as? TaskRequest.CandidateConfiguration
    request?.operation == TaskRequest.CandidateConfiguration.LIBRARY_SYNC && request.context.selectionToken == token
}.maxByOrNull { it.scheduling.sequence.value }
