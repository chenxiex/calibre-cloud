package io.github.chenxiex.calibrecloud.tasks.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import androidx.core.content.edit
import io.github.chenxiex.calibrecloud.ApplicationDependencies
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.tasks.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Wakeups carry no source data. APPEND_OR_REPLACE makes a submission during the final empty
 * claim/worker completion append a successor rather than lose a KEEP wakeup. Waiting network
 * work has a separate constrained chain so it cannot delay a new local/user request.
 */
class BackgroundTasks(private val context: Context, private val dependencies: ApplicationDependencies) {
    private val platformState = context.getSharedPreferences("background-platform", Context.MODE_PRIVATE)
    fun platformWaiting(): Boolean = platformState.getBoolean("waiting", false)
    internal fun recordPlatformWaiting(waiting: Boolean) {
        platformState.edit { putBoolean("waiting", waiting) }
    }
    private val startup by lazy { StartupSync(dependencies.state, dependencies.taskCoordinator) {
        dependencies.oneDriveAuthorization.sessionId()
    } }
    suspend fun startupEnabled() = startup.startupEnabled()
    suspend fun setStartupEnabled(enabled: Boolean) = startup.setStartupEnabled(enabled)
    suspend fun onMainOpened() {
        startup.onMainOpened()
        wake()
    }
    suspend fun manualSync() = startup.manualSync()

    suspend fun wake(): Unit = enqueue(WAKE_NAME, false, 0)

    internal suspend fun scheduleWaiting(retryWake: Boolean): Boolean {
        val pending = dependencies.taskQueue.list().filter {
            it.record.state is TaskState.Waiting && dependencies.taskQueue.isActive(it.record)
        }
        val network = pending.filter { WaitingReason.NETWORK in (it.record.state as TaskState.Waiting).reasons }
        if (network.isEmpty()) return false
        if (retryWake) return true
        val delay = network.minOf { (it.retryAt - System.currentTimeMillis()).coerceAtLeast(10_000) }
        enqueue(WAIT_NAME, true, delay, retryWake = true)
        return false
    }

    internal suspend fun retryPlatform() = enqueue(PLATFORM_NAME, false, 10_000, retryWake = true)

    private suspend fun enqueue(name: String, network: Boolean, delay: Long, retryWake: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("retry_wake" to retryWake))
            .setConstraints(Constraints.Builder().apply {
                if (network) setRequiredNetworkType(NetworkType.CONNECTED)
            }.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(QUEUE_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .result.get()
        Unit
    }

    companion object {
        const val WAKE_NAME = "durable-queue-wakeup"
        const val PLATFORM_NAME = "durable-queue-platform-retry"
        const val WAIT_NAME = "durable-queue-network-retry"
        const val QUEUE_TAG = "durable-queue"
    }
}

/** Reads known local state only; source/version checks remain the handler's responsibility. */
class QueueConditions(
    private val context: Context,
    private val database: ApplicationStateDatabase,
    private val networkAvailable: () -> Boolean = { connected(context) },
) {
    fun waiting(record: TaskRecord): Set<WaitingReason> {
        val request = record.submission.request
        val backend = if (request is TaskRequest.CandidateConfiguration) request.context.backend else {
            database.readableDatabase.rawQuery("SELECT backend FROM library_bindings WHERE library_id = ?",
                arrayOf(request.libraryId?.value.toString())).use {
                if (it.moveToFirst() && it.getString(0) == "onedrive") BackendKind.ONEDRIVE else BackendKind.LOCAL
            }
        }
        return if (backend == BackendKind.ONEDRIVE && !networkAvailable()) setOf(WaitingReason.NETWORK) else emptySet()
    }
    companion object {
        fun connected(context: Context): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
