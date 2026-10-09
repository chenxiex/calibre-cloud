package io.github.chenxiex.calibrecloud.tasks.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.BuildConfig
import io.github.chenxiex.calibrecloud.tasks.api.TaskEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancelAndJoin
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.model.CopyKey
import java.util.concurrent.ConcurrentHashMap
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses only the Application's queue/lock. A stopped worker leaves durable recovery evidence. */
class QueueWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dependencies = (applicationContext as CalibreCloudApplication).dependencies
        dependencies.backgroundTasks.workerStarted()
        try {
            dependencies.taskCoordinator.restorePending()
            val pending = dependencies.taskQueue.list().any {
                it.record.state !is TaskState.Finished && it.record.state !is TaskState.Paused &&
                    dependencies.taskQueue.isActive(it.record)
            }
            if (!pending) return@withContext Result.success()
            setForeground(getForegroundInfo())
            dependencies.backgroundTasks.recordPlatformWaiting(false)
            coroutineScope {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val observed = ConcurrentHashMap.newKeySet<TaskId>()
                val diagnostics = launch(start = CoroutineStart.UNDISPATCHED) {
                    dependencies.taskQueue.events.collect { event ->
                        if (event is TaskEvent.Changed) {
                            val record = event.record
                            observed.add(record.id)
                            val entry = dependencies.taskQueue.get(record.id) ?: return@collect
                            val progress = (record.state as? TaskState.Running)?.progress
                            val message = "task=${record.id.value} library=${record.libraryId?.value} " +
                                "stage=${entry.stage.code} state=${record.state.javaClass.simpleName} " +
                                "bytes=${progress?.completed ?: 0} retry=${entry.attempts} " +
                                "elapsed_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                            if (BuildConfig.DEBUG) android.util.Log.d("QueueWorker", message)
                        }
                    }
                }
                try { dependencies.taskCoordinator.drain() } finally { diagnostics.cancelAndJoin() }
                // Terminal events can race collector cancellation. Reread committed outcomes once,
                // rather than relying on a best-effort progress event to supply the end record.
                for (id in observed) {
                    val entry = dependencies.taskQueue.get(id) ?: continue
                    val record = entry.record
                    val result = (record.state as? TaskState.Finished)?.result
                    val failure = (result as? TaskResult.Failed)?.failure
                    val reason = when (val error = failure?.error) {
                        is TaskError.Source -> error.error.kind.name
                        null -> "none"
                        else -> error.javaClass.simpleName
                    }
                    val request = record.submission.request as? TaskRequest.FormatCopy
                    val bytes = if (result == TaskResult.Completed && request != null)
                        dependencies.state.find(CopyKey(request.resource.book, request.resource.format))?.sizeBytes else null
                    val message = "task=${id.value} library=${record.libraryId?.value} stage=${entry.stage.code} " +
                        "state=${record.state.javaClass.simpleName} result=${result?.javaClass?.simpleName} " +
                        "reason=$reason bytes=${bytes ?: 0} retry=${entry.attempts} " +
                        "queue_elapsed_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                    if (failure != null) android.util.Log.w("QueueWorker", message)
                    else if (BuildConfig.DEBUG) android.util.Log.d("QueueWorker", message)
                }
            }
            if (dependencies.backgroundTasks.scheduleWaiting(inputData.getBoolean("retry_wake", false))) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Platform foreground restrictions or private recovery failure are not task success.
            // Retry is bounded by WorkManager's backoff; durable requests remain visible.
            dependencies.backgroundTasks.recordPlatformWaiting(true)
            android.util.Log.w("QueueWorker", "stage=background_wait attempt=$runAttemptCount reason=${failure.javaClass.simpleName}")
            if (inputData.getBoolean("retry_wake", false)) Result.retry() else {
                dependencies.backgroundTasks.retryPlatform()
                Result.success()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            applicationContext.getString(R.string.queue_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.queue_notification_title))
            .setContentText(applicationContext.getString(R.string.queue_notification_text))
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        const val CHANNEL = "queue-execution"
        const val NOTIFICATION = 9
    }
}
