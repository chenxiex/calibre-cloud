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
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.storage.api.CopyReadResult
import io.github.chenxiex.calibrecloud.storage.api.CopyReader
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.storage.cache.CleanupPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DownloadCopyStatus { AVAILABLE, MISSING, FAILED }
data class DownloadListEntry(val copy: DownloadedCopy, val status: DownloadCopyStatus)

/**
 * The minimal "已下载文件" list (R12): the current library's complete copies from the manifest, usable
 * without a full import. Reading it and checking each shown copy are private reads; only a confirmed
 * removal changes anything, and it removes application copies, never source files.
 */
class DownloadViewModel(
    private val state: ApplicationStateRepository,
    private val reader: CopyReader,
    private val maintenance: CacheMaintenance,
) : ViewModel() {
    var selection by mutableStateOf<LibrarySelection?>(null)
        private set
    var entries by mutableStateOf<List<DownloadListEntry>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    /** Index of the first shown entry; always the start of a page of [capacity] entries. */
    var offset by mutableIntStateOf(0)
        private set
    var capacity by mutableIntStateOf(0)
        private set
    var readFailed by mutableStateOf(false)
        private set
    var removal by mutableStateOf<CleanupPlan?>(null)
        private set
    var removalResult by mutableStateOf<Boolean?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set
    private val reads = Mutex()

    fun restore() {
        viewModelScope.launch { reads.withLock { refresh(offset) } }
    }

    fun onMeasured(value: Int) {
        if (value <= 0 || value == capacity) return
        capacity = value
        restore()
    }

    fun showPage(page: Int) {
        if (capacity <= 0 || page < 0) return
        viewModelScope.launch { reads.withLock { refresh(page * capacity) } }
    }

    /** Previews removing the entry's format, or every format of its book with [allFormats]. */
    fun previewRemoval(entry: DownloadListEntry, allFormats: Boolean = false) {
        if (submitting) return
        viewModelScope.launch {
            try {
                removal = maintenance.previewCopies(setOf(entry.copy.key.book), if (allFormats) null else setOf(entry.copy.key.format))
                removalResult = if (removal == null) false else null
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                removalResult = false
            }
        }
    }

    fun cancelRemoval() {
        removal = null
    }

    fun confirmRemoval() {
        val plan = removal ?: return
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try {
                removalResult = maintenance.execute(plan)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                removalResult = false
            } finally {
                removal = null
                submitting = false
                restore()
            }
        }
    }

    private suspend fun refresh(requested: Int) {
        try {
            val before = state.current()
            val switched = before?.token != selection?.token || before?.identity != selection?.identity
            val identity = before?.identity
            val size = capacity.coerceAtLeast(1)
            val count = identity?.let { state.countCopies(it.id) } ?: 0
            // A page left beyond the end after a removal shows the last page instead.
            val start = if (switched) 0 else minOf(requested, lastPageStart(count, size)).coerceAtLeast(0) / size * size
            val copies = identity?.let { state.listCopies(it.id, size.coerceAtMost(200), start) }.orEmpty()
            val checked = copies.map { copy ->
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
            if (state.current()?.token != before?.token) return
            if (switched) {
                removal = null
                removalResult = null
            }
            selection = before
            entries = checked
            total = count
            offset = start
            readFailed = false
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            entries = emptyList()
            readFailed = true
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(DownloadViewModel(dependencies.state, dependencies.copyReader, dependencies.maintenance))!!
            }
        }
    }
}
