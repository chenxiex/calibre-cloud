package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.auth.*
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Retains one coordinator across Activity recreation; encrypted pending requests survive process death. */
class OneDriveAuthorizationViewModel(
    private val authorization: OneDriveAuthorization,
    private val onAuthorized: suspend () -> Unit = {},
) : ViewModel() {
    var status by mutableStateOf(LoginStatus.UNSIGNED)
        private set
    var issue by mutableStateOf<LoginIssue?>(null)
        private set
    private var pending by mutableIntStateOf(0)
    val busy: Boolean get() = pending > 0
    private var operation: Job? = null

    init {
        restore()
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                restore()
            }
        }
    }

    fun restore() { if (!busy) enqueue { authorization.restore() } }
    fun login(chooseAccount: Boolean = false, launchBrowser: (Intent) -> Unit) = enqueue {
        authorization.begin(chooseAccount)?.let {
            publish()
            try { launchBrowser(it) } catch (_: android.content.ActivityNotFoundException) {
                authorization.cancel(LoginIssue.NO_BROWSER)
            }
        }
    }
    fun callback(address: String) = enqueue {
        authorization.callback(address) { publish() }
        resumeIfAuthorized()
    }
    fun cancel() = enqueue { authorization.cancel() }
    fun refresh() = enqueue {
        authorization.refresh() { publish() }
        resumeIfAuthorized()
    }

    /** Work waiting for login continues once the user has signed in again. */
    private suspend fun resumeIfAuthorized() {
        if (authorization.status == LoginStatus.AUTHORIZED) onAuthorized()
    }

    private fun enqueue(action: suspend () -> Unit) {
        val previous = operation
        pending++
        operation = viewModelScope.launch {
            try {
                previous?.join()
                action()
            } finally {
                publish()
                pending--
            }
        }
    }

    private fun publish() {
        status = authorization.status
        issue = authorization.issue
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(OneDriveAuthorizationViewModel(dependencies.oneDriveAuthorization,
                    dependencies.backgroundTasks::resumeAuthorizationWaits))!!
            }
        }
    }
}
