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
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.storage.cache.CleanupKind
import io.github.chenxiex.calibrecloud.storage.cache.CleanupPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class CleanupResult { COMPLETED, FAILED, UNAVAILABLE }

/** The displayed confirmation holds the exact maintenance plan until explicit submission. */
class CleanupViewModel(private val maintenance: CacheMaintenance) : ViewModel() {
    var plan by mutableStateOf<CleanupPlan?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<CleanupResult?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun preview(kind: CleanupKind) {
        if (busy || kind == CleanupKind.COPIES || kind == CleanupKind.LIBRARY) return
        busy = true
        result = null
        plan = null
        viewModelScope.launch {
            try {
                plan = when (kind) {
                    CleanupKind.METADATA -> maintenance.previewMetadata()
                    CleanupKind.OTHER_LIBRARIES -> maintenance.previewOtherLibraries()
                    CleanupKind.COPIES, CleanupKind.LIBRARY -> null
                }
                if (plan == null) result = CleanupResult.UNAVAILABLE
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                result = CleanupResult.FAILED
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        if (busy) return
        plan = null
        result = null
    }

    fun confirm() {
        val displayed = plan ?: return
        if (busy) return
        busy = true
        result = null
        viewModelScope.launch {
            try {
                result = if (maintenance.execute(displayed)) CleanupResult.COMPLETED else CleanupResult.FAILED
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                result = CleanupResult.FAILED
            } finally {
                // A failed operation may already have removed private state; reread it as well.
                revision++
                plan = null
                busy = false
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(CleanupViewModel(dependencies.maintenance))!!
            }
        }
    }
}
