package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.metadata.ImportedLibrary
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads only private imported state. Column configuration never writes to the source library. */
class MetadataViewModel(
    private val state: ApplicationStateRepository,
    private val metadata: MetadataRepository,
) : ViewModel() {
    var selection by mutableStateOf<LibrarySelection?>(null)
        private set
    var imported by mutableStateOf<ImportedLibrary?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var readFailed by mutableStateOf(false)
        private set
    var configurationFailed by mutableStateOf(false)
        private set
    private val mutex = Mutex()

    fun restore() {
        viewModelScope.launch { mutex.withLock { refresh() } }
    }

    fun selectColumn(column: CustomColumnId?) {
        val displayed = imported ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                mutex.withLock {
                    configurationFailed = !metadata.selectReadColumn(displayed, column)
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
            val after = state.current()
            // A switched selection must not receive a read begun for its predecessor.
            selection = after
            imported = updated.takeIf {
                before?.token == after?.token && it?.identity == after?.identity
            }
            readFailed = false
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            selection = null
            imported = null
            readFailed = true
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(MetadataViewModel(dependencies.state, dependencies.metadata))!!
            }
        }
    }
}
