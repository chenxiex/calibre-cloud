package io.github.chenxiex.calibrecloud.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.LastOpened
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason

/** The notification title of a status that needs the user. */
internal fun openNoticeTitle(context: Context, status: OpenStatus): String = when (status) {
    is OpenStatus.Failed -> context.getString(R.string.open_notice_failed, status.title)
    is OpenStatus.Downloading -> context.getString(R.string.open_notice_waiting, status.title)
}

/** Why the book waits or could not be opened, as the notification text. */
internal fun openStatusText(context: Context, status: OpenStatus): String = when (status) {
    is OpenStatus.Downloading -> context.getString(when {
        status.task is TaskState.Paused -> R.string.open_paused
        else -> {
            val reasons = (status.task as? TaskState.Waiting)?.reasons.orEmpty()
            when {
                WaitingReason.LOGIN in reasons -> R.string.open_waiting_login
                WaitingReason.DIRECTORY_AUTHORIZATION in reasons -> R.string.open_waiting_authorization
                WaitingReason.NETWORK in reasons -> R.string.open_waiting_network
                WaitingReason.THROTTLED in reasons -> R.string.open_waiting_throttled
                else -> R.string.open_waiting
            }
        }
    })
    is OpenStatus.Failed -> when (status.problem) {
        OpenProblem.NO_FORMAT -> context.getString(R.string.open_no_format)
        OpenProblem.NO_APP -> context.getString(R.string.open_no_app, status.format?.value.orEmpty())
        OpenProblem.LAUNCH_FAILED -> context.getString(R.string.open_launch_failed)
        OpenProblem.COPY_UNREADABLE -> context.getString(R.string.open_copy_unreadable)
        OpenProblem.NO_METADATA -> context.getString(R.string.open_no_metadata)
        OpenProblem.UNAVAILABLE -> context.getString(R.string.open_unavailable)
        OpenProblem.DOWNLOAD_FAILED -> context.getString(downloadFailure(status))
    }
}

/** Why the download for opening ended without a copy; nothing here claims an older copy exists. */
private fun downloadFailure(status: OpenStatus.Failed): Int {
    val result = (status.task as? TaskState.Finished)?.result
    if (result is TaskResult.Cancelled) return R.string.open_download_cancelled
    return when (((result as? TaskResult.Failed)?.failure?.error as? TaskError.Source)?.error?.kind) {
        StorageErrorKind.NO_NETWORK -> R.string.open_download_network
        StorageErrorKind.THROTTLED -> R.string.open_download_throttled
        StorageErrorKind.LOGIN_REQUIRED -> R.string.open_waiting_login
        StorageErrorKind.AUTHORIZATION_EXPIRED ->
            if (status.backend == BackendKind.LOCAL) R.string.open_waiting_authorization else R.string.open_waiting_login
        StorageErrorKind.SOURCE_MISSING -> R.string.open_download_missing
        StorageErrorKind.VERSION_CONFLICT -> R.string.open_download_changed
        StorageErrorKind.INSUFFICIENT_SPACE -> R.string.open_download_space
        StorageErrorKind.CORRUPT_CONTENT -> R.string.open_download_corrupt
        else -> R.string.open_download_failed
    }
}

/** The "更多" page that resolves the shown condition, opened from the notification; null when none applies. */
internal fun openMoreTarget(status: OpenStatus): Int? {
    val reasons = ((status as? OpenStatus.Downloading)?.task as? TaskState.Waiting)?.reasons.orEmpty()
    val failure = (((status as? OpenStatus.Failed)?.task as? TaskState.Finished)?.result as? TaskResult.Failed)?.failure?.error
    val directory = if (status.backend == BackendKind.LOCAL) MoreTarget.LOCAL_AUTHORIZATION else MoreTarget.ONEDRIVE_LOGIN
    return when {
        WaitingReason.LOGIN in reasons -> MoreTarget.ONEDRIVE_LOGIN
        WaitingReason.DIRECTORY_AUTHORIZATION in reasons -> MoreTarget.LOCAL_AUTHORIZATION
        (failure as? TaskError.Source)?.error?.kind == StorageErrorKind.LOGIN_REQUIRED -> MoreTarget.ONEDRIVE_LOGIN
        (failure as? TaskError.Source)?.error?.kind == StorageErrorKind.AUTHORIZATION_EXPIRED -> directory
        status is OpenStatus.Failed && status.problem == OpenProblem.NO_METADATA -> MoreTarget.SYNC
        else -> null
    }
}

/**
 * The continue-reading button between the bottom tabs: an open book over the last opened title, cut
 * with an ellipsis. It opens a book rather than showing a page, so it never takes the shown-tab state.
 */
@Composable
internal fun LastOpenedTab(value: LastOpened, modifier: Modifier, onClick: () -> Unit) {
    val description = stringResource(R.string.last_opened_description, value.title)
    Column(
        modifier.fillMaxHeight().testTag("nav_last_opened")
            .tap(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = TIGHT_GAP),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_open_book), null, Modifier.size(ICON_SIZE), tint = INK)
        Text(value.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Posts the reason of an open that needs the user as one replaceable notification; tapping it brings
 * the app back, at the page that resolves the condition when there is one ([EXTRA_MORE_TARGET]).
 */
internal object OpenNotifications {
    private const val CHANNEL = "open-problems"
    private const val NOTIFICATION = 10
    const val EXTRA_MORE_TARGET = "io.github.chenxiex.calibrecloud.more_target"

    /** False when the platform does not let the app post notifications. */
    fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun post(context: Context, status: OpenStatus) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.open_notice_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val target = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        openMoreTarget(status)?.let { target.putExtra(EXTRA_MORE_TARGET, it) }
        val intent = PendingIntent.getActivity(context, NOTIFICATION, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = openStatusText(context, status)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(openNoticeTitle(context, status))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        if (allowed(context)) manager.notify(NOTIFICATION, notification)
    }

    fun withdraw(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
}

/** The text of a batch notice: what was not done and why. */
internal fun batchNoticeText(context: Context, notice: BatchNotice): String = when (notice) {
    is BatchNotice.Downloads -> listOfNotNull(
        notice.noFormat.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.notice_downloads_no_format, it, it) },
        notice.rejected.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.notice_downloads_rejected, it, it) },
    ).joinToString(context.getString(R.string.notice_separator))
    BatchNotice.RemovalFailed -> context.getString(R.string.notice_removal_failed)
    BatchNotice.NoBooks -> context.getString(R.string.notice_no_books)
    BatchNotice.Unavailable -> context.getString(R.string.notice_unavailable)
}

/** Posts a batch action that was not fully done as one replaceable notification; tapping it brings the app back. */
internal object BatchNotifications {
    private const val CHANNEL = "batch-problems"
    private const val NOTIFICATION = 11

    fun post(context: Context, notice: BatchNotice) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.notice_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val target = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val intent = PendingIntent.getActivity(context, NOTIFICATION, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = batchNoticeText(context, notice)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notice_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        if (OpenNotifications.allowed(context)) manager.notify(NOTIFICATION, notification)
    }
}
