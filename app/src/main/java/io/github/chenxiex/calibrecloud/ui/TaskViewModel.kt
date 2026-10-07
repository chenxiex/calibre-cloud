package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import android.app.ActivityManager
import android.app.NotificationManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** State transitions are immediate; progress-only changes share a two-second display gate. */
class TaskViewModel(
    private val queue: DurableTaskQueue,
    private val startupEnabled: suspend () -> Boolean,
    private val setStartupEnabled: suspend (Boolean) -> Unit,
    private val wake: suspend () -> Unit,
    private val manualSync: suspend () -> Boolean,
    application: Application,
    private val platformWaiting: () -> Boolean,
) : AndroidViewModel(application) {
    var records by mutableStateOf<List<TaskRecord>>(emptyList())
        private set
    var automaticSync by mutableStateOf(false)
        private set
    var settingsLoaded by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var operationFailed by mutableStateOf(false)
        private set
    var syncUnavailable by mutableStateOf(false)
        private set
    var notificationsEnabled by mutableStateOf(true)
        private set
    var backgroundRestricted by mutableStateOf(false)
        private set
    var batteryOptimized by mutableStateOf(false)
        private set
    var platformBlocked by mutableStateOf(false)
        private set
    private var polling: Job? = null
    private var observing: Job? = null
    private val refreshLock = Mutex()
    private var progressUpdatedAt = 0L

    fun setVisible(visible: Boolean) {
        polling?.cancel()
        observing?.cancel()
        if (!visible) return
        polling = viewModelScope.launch {
            while (true) {
                refresh()
                delay(2_000)
            }
        }
        observing = viewModelScope.launch { queue.events.collect { refresh() } }
    }

    private suspend fun refresh() = refreshLock.withLock {
        try {
            val latest = queue.list().map { it.record }
            val now = SystemClock.elapsedRealtime()
            val showProgress = now - progressUpdatedAt >= 2_000
            records = latest.map { record ->
                val previous = records.firstOrNull { it.id == record.id }
                val current = record.state as? TaskState.Running
                val old = previous?.state as? TaskState.Running
                if (!showProgress && current != null && old?.stage == current.stage) {
                    record.copy(state = current.copy(progress = old.progress))
                } else record
            }.sortedBy { when (it.state) {
                is TaskState.Running -> 0
                is TaskState.Finished -> 2
                else -> 1
            } }
            if (showProgress) progressUpdatedAt = now
            automaticSync = startupEnabled()
            refreshCapabilities()
            settingsLoaded = true
            failed = false
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            failed = true
        }
    }

    fun refreshCapabilities() {
        val context = getApplication<Application>()
        notificationsEnabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        batteryOptimized = !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        platformBlocked = platformWaiting()
    }

    fun retryBackground() {
        viewModelScope.launch {
            try {
                wake()
                refresh()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) { failed = true }
        }
    }

    fun control(record: TaskRecord, command: TaskControl) {
        operationFailed = false
        viewModelScope.launch {
            try {
                val accepted = queue.control(record.id, command)
                if (accepted) wake()
                refresh()
                if (!accepted) operationFailed = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                operationFailed = true
            }
        }
    }

    fun toggleStartup() {
        viewModelScope.launch {
            try {
                setStartupEnabled(!automaticSync)
                automaticSync = startupEnabled()
                failed = false
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) { failed = true }
        }
    }

    fun synchronize() {
        viewModelScope.launch {
            try {
                syncUnavailable = !manualSync()
                refresh()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) { failed = true }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                val background = dependencies.backgroundTasks
                return modelClass.cast(TaskViewModel(dependencies.taskQueue, background::startupEnabled,
                    background::setStartupEnabled, background::wake, background::manualSync, context.applicationContext as Application,
                    background::platformWaiting))!!
            }
        }
    }
}
