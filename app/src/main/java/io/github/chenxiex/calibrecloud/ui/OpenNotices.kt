package io.github.chenxiex.calibrecloud.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        status is OpenStatus.Failed && status.problem == OpenProblem.NO_METADATA ->
            if (status.backend == BackendKind.LOCAL) MoreTarget.LOCAL_AUTHORIZATION else MoreTarget.ONEDRIVE_TASKS
        else -> null
    }
}

/** The last opened book of the current library in the bottom bar: its cover, or its title in a frame. */
@Composable
internal fun LastOpenedTab(value: LastOpened, cover: Bitmap?, modifier: Modifier, onClick: () -> Unit) {
    val description = stringResource(R.string.last_opened_description, value.title)
    Box(
        modifier.fillMaxHeight().testTag("nav_last_opened")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            val image = remember(cover) { cover.asImageBitmap() }
            Image(image, null, Modifier.fillMaxHeight().aspectRatio(1f / COVER_ASPECT).border(1.dp, Color.Black), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.9f).border(1.dp, Color.Black).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(value.title, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
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
