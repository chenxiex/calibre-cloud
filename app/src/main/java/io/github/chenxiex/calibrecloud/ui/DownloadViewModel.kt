package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.storage.api.CopyReadResult
import io.github.chenxiex.calibrecloud.storage.api.CopyReader
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class DownloadOption(val key: CopyKey, val title: String, val sizeBytes: Long?)
enum class DownloadCopyStatus { AVAILABLE, MISSING, FAILED }
data class DownloadListEntry(val copy: DownloadedCopy, val status: DownloadCopyStatus)

/** All restored data and copy checks are private reads. Only explicit actions run source tasks. */
class DownloadViewModel(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val reader: CopyReader,
    private val queue: DurableTaskQueue,
    private val coordinator: TaskCoordinator,
    private val submitCopy: suspend (CopyKey, UUID) -> SubmissionResult,
) : ViewModel() {
    var selection by mutableStateOf<LibrarySelection?>(null)
        private set
    var options by mutableStateOf<List<DownloadOption>>(emptyList())
        private set
    var entries by mutableStateOf<List<DownloadListEntry>>(emptyList())
        private set
    var listPage by mutableIntStateOf(0)
        private set
    var listHasNext by mutableStateOf(false)
        private set
    var record by mutableStateOf<TaskRecord?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set
    var rejected by mutableStateOf(false)
        private set
    var readFailed by mutableStateOf(false)
        private set
    private var observer: Job? = null
    private val reads = Mutex()

    fun restore() {
        viewModelScope.launch {
            reads.withLock { refresh() }
        }
    }

    fun paginate(page: Int) {
        if (page < 0) return
        viewModelScope.launch { reads.withLock { refresh(page) } }
    }

    fun download(option: DownloadOption) {
        val token = selection?.token ?: return
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try {
                val result = submitCopy(option.key, token)
                val id = when (result) {
                    is SubmissionResult.Created -> result.taskId
                    is SubmissionResult.Reused -> result.taskId
                    is SubmissionResult.Promoted -> result.taskId
                    is SubmissionResult.Rejected -> { rejected = true; return@launch }
                }
                rejected = false
                observe(id, token)
                wake()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                rejected = true
            } finally {
                submitting = false
                restore()
            }
        }
    }

    fun control(command: TaskControl) {
        val displayed = record ?: return
        val token = selection?.token ?: return
        viewModelScope.launch {
            val selected = state.current()
            if (selected?.token != token || selected.identity?.id != displayed.submission.request.libraryId) return@launch
            if (queue.control(displayed.id, command) && command in setOf(TaskControl.RESUME, TaskControl.RETRY)) {
                wake()
            }
            restore()
        }
    }

    fun runQueued() {
        wake()
    }

    private fun wake() {
        viewModelScope.launch {
            try {
                coordinator.drain()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                rejected = true
            } finally {
                restore()
            }
        }
    }

    private suspend fun refresh(requestedPage: Int = listPage) {
        try {
            coordinator.restorePending()
            val before = state.current()
            val switched = before?.token != selection?.token || before?.identity != selection?.identity
            val page = if (switched) 0 else requestedPage
            val imported = metadata.currentImport()?.takeIf { it.identity == before?.identity }
            val updatedOptions = imported?.metadata?.books.orEmpty().flatMap { book ->
                book.formats.map { format -> DownloadOption(
                    CopyKey(BookKey(imported!!.identity.id, book.sourceId, book.sourceUuid), format.format),
                    book.title, format.sizeBytes,
                ) }
            }
            val copies = before?.identity?.let { state.listCopies(it.id, LIST_SIZE + 1, page * LIST_SIZE) }.orEmpty()
            val updatedEntries = copies.take(LIST_SIZE).map { copy ->
                val status = when (val read = reader.read(copy.key)) {
                    is CopyReadResult.Available -> {
                        withContext(Dispatchers.IO + NonCancellable) { read.handle.close() }
                        DownloadCopyStatus.AVAILABLE
                    }
                    CopyReadResult.Missing -> DownloadCopyStatus.MISSING
                    is CopyReadResult.Failed -> DownloadCopyStatus.FAILED
                }
                DownloadListEntry(copy, status)
            }
            val latest = before?.identity?.let { identity -> queue.list().filter {
                it.record.submission.request is TaskRequest.FormatCopy && it.record.submission.request.libraryId == identity.id
            }.maxByOrNull { it.record.scheduling.sequence.value }?.record }
            if (state.current()?.token != before?.token) return
            if (switched) {
                observer?.cancel()
                record = null
                rejected = false
            }
            selection = before
            options = updatedOptions
            entries = updatedEntries
            listPage = page
            listHasNext = copies.size > LIST_SIZE
            readFailed = false
            if (latest != null && record?.id != latest.id) observe(latest.id, before!!.token)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            options = emptyList()
            entries = emptyList()
            readFailed = true
        }
    }

    private fun observe(id: TaskId, token: UUID) {
        observer?.cancel()
        observer = viewModelScope.launch {
            queue.observe(id).collect { updated ->
                val current = state.current()
                if (current?.token == token && current.identity?.id == updated.submission.request.libraryId) {
                    record = updated
                    if (updated.state is TaskState.Finished) restore()
                } else {
                    record = null
                }
            }
        }
    }

    companion object {
        private const val LIST_SIZE = 2

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(DownloadViewModel(dependencies.state, dependencies.metadata,
                    dependencies.copyReader, dependencies.taskQueue, dependencies.taskCoordinator,
                    { key, token -> dependencies.copyService.submit(key, token) }))!!
            }
        }
    }
}
