package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The OneDrive directory chooser of the library being added. Restoration reads private candidate results; opening the
 * step ([browseIfEmpty]), reloading, entering a directory or choosing one submits work. Paging only slices the complete stored listing into pages
 * of [pageSize] directories, which the page sets from its measured space.
 */
class OneDriveLibraryViewModel(private val service: OneDriveCandidateService) : ViewModel() {
    var record by mutableStateOf<TaskRecord?>(null)
        private set
    var page by mutableStateOf<OneDriveBrowseResult?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set
    var rejected by mutableStateOf(false)
        private set
    private var listing: OneDriveBrowseResult? = null
    private var localPage = 0
    var pageSize by mutableIntStateOf(OneDriveCandidateTaskHandler.PAGE_SIZE)
        private set
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
        val updatedListing = service.currentPage()
        if (updatedListing?.parentItemId != listing?.parentItemId || updatedListing?.location != listing?.location) localPage = 0
        listing = updatedListing
        // A new addition starts from the drive root again.
        if (updatedListing == null) parents = emptyList()
        page = listing?.pageAt(localPage, pageSize) ?: listing?.pageAt(0, pageSize).also { localPage = 0 }
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

    /**
     * Lists the drive root when the restored state has neither a listing nor a browse under way, so the
     * directory step loads by itself after login. A failed browse stays shown until [browse] retries it.
     */
    fun browseIfEmpty() {
        viewModelScope.launch {
            refresh()
            val state = record?.state
            if (page == null && !submitting && (state == null || state is TaskState.Finished && state.result !is TaskResult.Failed)) browse()
        }
    }

    /** Whether a directory request is being submitted or waits in the queue to run. */
    val loading: Boolean get() = submitting || record?.state.let { it == TaskState.Queued || it is TaskState.Running }

    fun enter(itemId: String) {
        val previous = page?.parentItemId ?: return
        submit(parents + previous) { service.browse(itemId) }
    }

    /** Lists the shown directory again, for directories changed since it was loaded; the way back up stays. */
    fun reload() {
        val shown = page?.parentItemId ?: return
        val chain = parents
        submit(chain) { if (chain.isEmpty()) service.browse() else service.browse(shown) }
    }

    fun up() {
        val parent = parents.lastOrNull() ?: return
        submit(parents.dropLast(1)) { service.browse(parent) }
    }

    /** Keeps the first shown directory on screen when the measured page size changes. */
    fun onMeasured(size: Int) {
        if (size <= 0 || size == pageSize) return
        val first = localPage * pageSize
        pageSize = size
        localPage = first / size
        page = listing?.pageAt(localPage, size) ?: listing?.pageAt(0, size).also { localPage = 0 }
    }

    /** Number of pages of the complete listing; at least one. */
    val pageCount: Int get() = listing?.items?.size?.let { maxOf(1, (it + pageSize - 1) / pageSize) } ?: 1

    fun paginate(target: Int) {
        if (submitting) return
        val updated = listing?.pageAt(target, pageSize) ?: return
        localPage = target
        page = updated
        rejected = false
    }

    /** [onChosen] runs after the directory became the addition's root. */
    fun choose(itemId: String, onChosen: () -> Unit = {}) {
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
                if (!rejected) onChosen()
            } finally {
                submitting = false
            }
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
                    service.coordinator.requestRun()
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
                (context.applicationContext as CalibreCloudApplication).dependencies.let {
                    OneDriveLibraryViewModel(it.oneDriveTasks)
                },
            )!!
        }
    }
}
