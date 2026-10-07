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
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationState
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryAuthorization
import io.github.chenxiex.calibrecloud.storage.local.createLocalDirectoryAuthorization
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns in-flight selection across Activity recreation; serializes operations and result delivery. */
class LocalDirectoryAuthorizationViewModel(
    private val authorization: LocalDirectoryAuthorization,
    private val onAuthorized: suspend () -> Unit = {},
) : ViewModel() {
    var state by mutableStateOf(DirectoryAuthorizationState(DirectoryAuthorizationStatus.UNSELECTED))
        private set
    private var pending by mutableIntStateOf(0)
    val busy: Boolean get() = pending > 0
    private var operation: Job? = null

    fun refresh() {
        if (busy) return
        enqueue { authorization.restore().copy(selectionIssue = state.selectionIssue) }
    }

    fun select(treeUri: String?, resultFlags: Int) {
        enqueue {
            authorization.select(treeUri, resultFlags).also {
                // Work waiting for directory authorization continues after a successful grant.
                if (it.selectionIssue == null && it.status in setOf(
                        DirectoryAuthorizationStatus.AUTHORIZED, DirectoryAuthorizationStatus.READ_ONLY)) onAuthorized()
            }
        }
    }

    private fun enqueue(action: suspend () -> DirectoryAuthorizationState) {
        val previous = operation
        pending++
        operation = viewModelScope.launch {
            try {
                previous?.join()
                state = action()
            } finally {
                pending--
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val applicationContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    modelClass.cast(LocalDirectoryAuthorizationViewModel(createLocalDirectoryAuthorization(applicationContext),
                        (applicationContext as CalibreCloudApplication).dependencies.backgroundTasks::resumeAuthorizationWaits))!!
            }
        }
    }
}
