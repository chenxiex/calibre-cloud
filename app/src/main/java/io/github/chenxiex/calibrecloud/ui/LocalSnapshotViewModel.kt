package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.background.StartupSync
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** Restoring reads only private queue/configuration. Only explicit actions wake source processing. */
class LocalSnapshotViewModel(
    private val state: ApplicationStateRepository,
    private val queue: DurableTaskQueue,
    private val coordinator: TaskCoordinator,
    private val sync: StartupSync,
) : ViewModel() {
    var record by mutableStateOf<TaskRecord?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set
    var rejected by mutableStateOf(false)
        private set
    private var selectionToken: UUID? = null
    private var observer: Job? = null

    fun restore() {
        viewModelScope.launch {
            val selected = state.current()
            val token = selected?.takeIf { it.backend == BackendKind.LOCAL }?.token
            if (token != selectionToken) {
                observer?.cancel()
                selectionToken = token
                record = null
                rejected = false
            }
            val latest = queue.list().filter {
                val request = it.record.submission.request as? TaskRequest.CandidateConfiguration
                request?.context?.selectionToken == token && request?.operation == TaskRequest.CandidateConfiguration.LIBRARY_SYNC
            }.maxByOrNull { it.record.scheduling.sequence.value }?.record
            if (latest != null) observe(latest.id)
        }
    }

    fun acquire() {
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try {
                val selected = state.current()
                if (selected == null || selected.backend != BackendKind.LOCAL || selected.location == null) {
                    rejected = true
                    return@launch
                }
                selectionToken = selected.token
                val id = sync.request(TaskOrigin.MANUAL_SYNC) ?: run { rejected = true; return@launch }
                rejected = false
                observe(id)
                coordinator.requestRun()
            } finally {
                submitting = false
            }
        }
    }

    fun control(command: TaskControl) {
        val id = record?.id ?: return
        viewModelScope.launch {
            if (queue.control(id, command) && command in setOf(TaskControl.RESUME, TaskControl.RETRY)) {
                coordinator.requestRun()
            }
            restore()
        }
    }

    private fun observe(id: TaskId) {
        observer?.cancel()
        observer = viewModelScope.launch {
            queue.observe(id).collect { updated ->
                val token = (updated.submission.request as TaskRequest.CandidateConfiguration).context.selectionToken
                if (state.current()?.token == token) record = updated
                else if (record?.id == id) record = null
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(LocalSnapshotViewModel(dependencies.state, dependencies.taskQueue,
                    dependencies.taskCoordinator, dependencies.librarySync))!!
            }
        }
    }
}
