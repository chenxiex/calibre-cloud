package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateService
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveBrowseResult
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Restoration reads private candidate results; only explicit controls wake source tasks. */
class OneDriveLibraryViewModel(private val service: OneDriveCandidateService) : ViewModel() {
    var record by mutableStateOf<TaskRecord?>(null)
        private set
    var page by mutableStateOf<OneDriveBrowseResult?>(null)
        private set
    var rootChosen by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var rejected by mutableStateOf(false)
        private set
    private var listing: OneDriveBrowseResult? = null
    private var localPage = 0
    private var observer: Job? = null
    private var observedId: TaskId? = null
    private val refreshMutex = Mutex()
    private var parents by mutableStateOf<List<String>>(emptyList())
    private var pendingNavigation: Pair<TaskId, List<String>>? = null
    val canGoUp: Boolean get() = parents.isNotEmpty()

    fun restore() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() = refreshMutex.withLock {
        rootChosen = service.currentLocation() != null
        val updatedListing = service.currentPage()
        if (updatedListing?.parentItemId != listing?.parentItemId || updatedListing?.location != listing?.location) localPage = 0
        listing = updatedListing
        page = listing?.pageAt(localPage) ?: listing?.pageAt(0).also { localPage = 0 }
        val latest = service.currentRecord()
        if (latest?.id != observedId) {
            observer?.cancel()
            observedId = latest?.id
            latest?.let { observe(it.id) }
        }
        commitNavigation(latest)
        record = latest
    }

    fun browse() = submit(emptyList()) { service.browse() }

    fun enter(itemId: String) {
        val previous = page?.parentItemId ?: return
        submit(parents + previous) { service.browse(itemId) }
    }

    fun up() {
        val parent = parents.lastOrNull() ?: return
        submit(parents.dropLast(1)) { service.browse(parent) }
    }

    fun paginate(target: Int) {
        if (submitting) return
        val updated = listing?.pageAt(target) ?: return
        localPage = target
        page = updated
        rejected = false
    }

    fun choose(itemId: String) {
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try {
                rejected = !service.choose(itemId)
                if (!rejected) {
                    parents = emptyList()
                    pendingNavigation = null
                }
                refresh()
            } finally {
                submitting = false
            }
        }
    }

    fun acquire() = submit { service.acquire() }

    fun runQueued() {
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try { service.coordinator.drain(); refresh() } finally { submitting = false }
        }
    }

    fun control(command: TaskControl) {
        val id = record?.id ?: return
        viewModelScope.launch {
            if (service.queue.control(id, command) && command in setOf(TaskControl.RESUME, TaskControl.RETRY)) {
                service.coordinator.drain()
            }
            refresh()
        }
    }

    private fun submit(navigation: List<String>? = null, action: suspend () -> TaskId?) {
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try {
                val id = action()
                rejected = id == null
                if (id != null) {
                    if (navigation != null) pendingNavigation = id to navigation
                    observe(id)
                    service.coordinator.drain()
                }
                refresh()
            } finally {
                submitting = false
            }
        }
    }

    /** Failed or cancelled navigation keeps the last displayed directory's parent chain. */
    private fun commitNavigation(latest: TaskRecord?) {
        val pending = pendingNavigation ?: return
        if (latest == null || latest.id != pending.first) return
        val finished = latest.state as? TaskState.Finished ?: return
        if (finished.result == TaskResult.Completed) parents = pending.second
        pendingNavigation = null
    }

    private fun observe(id: TaskId) {
        observedId = id
        observer?.cancel()
        observer = viewModelScope.launch {
            service.queue.observe(id).collect {
                // Serialize private refreshes and track the observed ID independently from the displayed record.
                restore()
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                OneDriveLibraryViewModel((context.applicationContext as CalibreCloudApplication).dependencies.oneDriveTasks),
            )!!
        }
    }
}
