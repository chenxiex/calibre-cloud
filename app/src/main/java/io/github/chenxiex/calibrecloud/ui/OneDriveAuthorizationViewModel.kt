package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.auth.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Retains one coordinator across Activity recreation; encrypted pending requests survive process death. */
class OneDriveAuthorizationViewModel(private val authorization: OneDriveAuthorization) : ViewModel() {
    var status by mutableStateOf(LoginStatus.UNSIGNED)
        private set
    var issue by mutableStateOf<LoginIssue?>(null)
        private set
    private var pending by mutableStateOf(0)
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
    fun login(launchBrowser: (Intent) -> Unit) = enqueue {
        authorization.begin()?.let {
            publish()
            try { launchBrowser(it) } catch (_: android.content.ActivityNotFoundException) {
                authorization.cancel(LoginIssue.NO_BROWSER)
            }
        }
    }
    fun callback(address: String) = enqueue { authorization.callback(address) { publish() } }
    fun cancel() = enqueue { authorization.cancel() }
    fun refresh() = enqueue { authorization.refresh() { publish() } }

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

    override fun onCleared() { authorization.close() }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                OneDriveAuthorizationViewModel(OneDriveAuthorization(
                    context.applicationContext, OneDriveOAuthConfiguration.fromBuildConfiguration(),
                    EncryptedAuthStateStore(context.applicationContext),
                )),
            )!!
        }
    }
}
