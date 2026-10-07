package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

data class CoverOption(val key: BookKey, val title: String)

/** Cache reads never enqueue work; only the visible page explicitly requests its missing cover. */
class CoverViewModel(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
    private val covers: CoverRepository,
    private val queue: DurableTaskQueue,
    private val coordinator: TaskCoordinator,
    private val submitCover: suspend (BookKey, UUID) -> SubmissionResult,
) : ViewModel() {
    var selection by mutableStateOf<LibrarySelection?>(null)
        private set
    var books by mutableStateOf<List<CoverOption>>(emptyList())
        private set
    var page by mutableIntStateOf(0)
        private set
    var image by mutableStateOf<Bitmap?>(null)
        private set
    var record by mutableStateOf<TaskRecord?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var visible = false
    private var revision = 0L
    private var observer: Job? = null
    private var loading: Job? = null

    fun setVisible(value: Boolean) {
        visible = value
        revision++
        loading?.cancel()
        observer?.cancel()
        busy = false
        if (value) restore()
    }

    fun paginate(value: Int) {
        if (value !in books.indices) return
        page = value
        revision++
        image = null
        record = null
        failed = false
        observer?.cancel()
        restore()
    }

    fun restore() {
        if (!visible) return
        loading?.cancel()
        observer?.cancel()
        val expected = ++revision
        loading = viewModelScope.launch {
            busy = true
            try {
                coordinator.restorePending()
                val selected = state.current()
                val imported = metadata.currentImport()?.takeIf { it.identity == selected?.identity }
                val options = imported?.metadata?.books.orEmpty().map { book ->
                    CoverOption(BookKey(imported!!.identity.id, book.sourceId, book.sourceUuid), book.title)
                }
                val index = if (selection?.token != selected?.token) 0 else page.coerceIn(0, maxOf(0, options.lastIndex))
                val option = options.getOrNull(index)
                val bitmap = option?.let { covers.read(it.key) }
                val latest = option?.let { displayed -> queue.list().map { it.record }.filter {
                    (it.submission.request as? TaskRequest.CoverLoad)?.book == displayed.key
                }.maxByOrNull { it.scheduling.sequence.value } }
                if (!valid(expected, selected?.token)) return@launch
                selection = selected
                books = options
                page = index
                image = bitmap
                record = latest
                failed = false
                if (option != null && selected != null) {
                    if (latest != null) {
                        observe(latest, selected.token, expected)
                        if (latest.state == TaskState.Queued) wake(expected, selected.token)
                    }
                    // A finished failure remains until explicit retry; never start an automatic retry loop.
                    if (bitmap == null && (latest == null || (latest.state as? TaskState.Finished)?.result == TaskResult.Completed)) {
                        val result = submitCover(option.key, selected.token)
                        if (!valid(expected, selected.token)) return@launch
                        val id = when (result) {
                            is SubmissionResult.Created -> result.taskId
                            is SubmissionResult.Reused -> result.taskId
                            is SubmissionResult.Promoted -> result.taskId
                            is SubmissionResult.Rejected -> { failed = true; null }
                        }
                        if (id != null) {
                            queue.get(id)?.record?.let { record = it; observe(it, selected.token, expected) }
                            wake(expected, selected.token)
                        }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (revision == expected && visible) failed = true
            } finally {
                if (revision == expected) busy = false
            }
        }
    }

    fun control(command: TaskControl) {
        val displayed = record ?: return
        val token = selection?.token ?: return
        val expected = revision
        viewModelScope.launch {
            try {
                if (!valid(expected, token)) return@launch
                if (queue.control(displayed.id, command) && command in setOf(TaskControl.RESUME, TaskControl.RETRY)) {
                    wake(expected, token)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (valid(expected, token)) failed = true
            }
        }
    }

    fun runQueued() {
        val token = selection?.token ?: return
        wake(revision, token)
    }

    private fun wake(expected: Long, token: UUID) {
        viewModelScope.launch {
            if (!valid(expected, token)) return@launch
            try {
                coordinator.requestRun()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (valid(expected, token)) failed = true
            }
        }
    }

    private fun observe(task: TaskRecord, token: UUID, expected: Long) {
        observer?.cancel()
        observer = viewModelScope.launch {
            try {
                queue.observe(task.id).collect { updated ->
                    if (!valid(expected, token)) return@collect
                    record = updated
                    if (updated.state is TaskState.Finished) {
                        val option = books.getOrNull(page) ?: return@collect
                        val bitmap = covers.read(option.key)
                        if (valid(expected, token)) image = bitmap
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (valid(expected, token)) failed = true
            }
        }
    }

    private suspend fun valid(expected: Long, token: UUID?): Boolean =
        visible && revision == expected && state.current()?.token == token

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(CoverViewModel(dependencies.state, dependencies.metadata,
                    dependencies.covers, dependencies.taskQueue, dependencies.taskCoordinator,
                    { key, token -> dependencies.coverService.submit(key, token) }))!!
            }
        }
    }
}
