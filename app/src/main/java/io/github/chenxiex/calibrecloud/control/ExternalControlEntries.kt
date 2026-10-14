package io.github.chenxiex.calibrecloud.control

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.ui.CalibreCloudTheme
import io.github.chenxiex.calibrecloud.ui.ConfirmPanel
import io.github.chenxiex.calibrecloud.ui.MainActivity
import io.github.chenxiex.calibrecloud.ui.MoreTarget
import io.github.chenxiex.calibrecloud.ui.OpenNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "ExternalControl"

/** Reads the request a URI carries; its operation is the host. */
internal fun parseRequest(uri: Uri?): ParsedRequest =
    if (uri == null || uri.isOpaque) ParsedRequest.Malformed else ExternalProtocol.parse(uri.host) { uri.getQueryParameter(it) }

/**
 * Carries out a read status request and posts the notification of a rejection; the request's file name
 * names the book in it. Logs only the outcome, never the token or the file name (R33).
 */
internal suspend fun markReadStatus(context: Context, request: ExternalRequest.MarkReadStatus): ExternalOutcome {
    val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
    val outcome = try {
        ExternalControl(AppExternalControlPort(dependencies)).markReadStatus(request)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        Log.w(TAG, "read status request failed: ${failure.javaClass.simpleName}")
        ExternalOutcome.Rejected(ExternalRejection.NOT_WRITABLE)
    }
    Log.i(TAG, "read status request: $outcome")
    if (outcome is ExternalOutcome.Rejected) ExternalNotifications.post(context, outcome.reason, request.file)
    return outcome
}

/**
 * The entry that keeps the user in the reader (R37, Q91): an explicit broadcast whose data is the
 * request URI. Registration needs the user's confirmation and therefore the Activity entry.
 */
class ExternalControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parsed = parseRequest(intent.data)
        val request = (parsed as? ParsedRequest.Valid)?.request
        if (request !is ExternalRequest.MarkReadStatus) {
            Log.i(TAG, "broadcast rejected: $parsed")
            ExternalNotifications.post(context, if (parsed is ParsedRequest.UnknownOperation) ExternalRejection.UNKNOWN_OPERATION
                else ExternalRejection.MALFORMED, null)
            return
        }
        val pending = goAsync()
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        dependencies.applicationScope.launch {
            try {
                markReadStatus(context.applicationContext, request)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The entry that brings this app forward or asks the user (R37, Q91, Q92): a `VIEW` of the request URI,
 * sent by the reader while it is in the foreground. A read status request is carried out and, when
 * accepted, shows the library page; a registration shows the confirmation over the reader and returns
 * to it either way. The Activity has its own task, so finishing returns to the caller rather than to
 * this app's library, and it draws nothing else.
 */
class ExternalControlActivity : ComponentActivity() {
    /** Asked right after a registration, since rejections are reported only by notification (Q88). */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    /** Asks for the notification permission when it is missing, then returns to the caller. */
    private fun finishRegistration() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finish()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = parseRequest(intent.data)
        when (val request = (parsed as? ParsedRequest.Valid)?.request) {
            is ExternalRequest.MarkReadStatus -> {
                // A recreated Activity already handled its request.
                if (savedInstanceState != null) {
                    finish()
                    return
                }
                lifecycleScope.launch {
                    val outcome = markReadStatus(applicationContext, request)
                    if (outcome == ExternalOutcome.Accepted) startActivity(Intent(this@ExternalControlActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_SHOW_LIBRARY, true))
                    finish()
                }
            }
            is ExternalRequest.Register -> setContent {
                CalibreCloudTheme {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { RegisterPrompt(request) }
                }
            }
            null -> {
                Log.i(TAG, "activity request rejected: $parsed")
                ExternalNotifications.post(this, if (parsed is ParsedRequest.UnknownOperation) ExternalRejection.UNKNOWN_OPERATION
                    else ExternalRejection.MALFORMED, null)
                finish()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun RegisterPrompt(request: ExternalRequest.Register) {
        val dependencies = (application as CalibreCloudApplication).dependencies
        val scope = rememberCoroutineScope()
        var enabled by remember { mutableStateOf<Boolean?>(null) }
        var replaces by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(request) {
            try {
                replaces = dependencies.externalControl.clients().any { it.name == request.name }
                enabled = dependencies.externalControl.enabled()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                failed = true
            }
        }
        ConfirmPanel("external_register", enabled != null && !busy, ::finish, {
            busy = true
            scope.launch {
                try {
                    ExternalControl(AppExternalControlPort(dependencies)).register(request)
                    Log.i(TAG, "registration confirmed")
                    finishRegistration()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    failed = true
                    busy = false
                }
            }
        }, title = stringResource(R.string.external_register_title), confirmLabel = stringResource(R.string.external_register_allow)) {
            Text(stringResource(R.string.external_register_text, request.name), Modifier.testTag("external_register_text"))
            if (replaces) Text(stringResource(R.string.external_register_replaces), Modifier.testTag("external_register_replaces"))
            if (enabled == false) Text(stringResource(R.string.external_register_disabled), Modifier.testTag("external_register_disabled"))
            if (failed) Text(stringResource(R.string.external_register_failed), Modifier.testTag("external_register_failed"))
        }
    }
}

/** One replaceable notification for the latest rejected request (Q88); tapping it opens the app. */
internal object ExternalNotifications {
    private const val CHANNEL = "external-requests"
    private const val NOTIFICATION = 13

    fun post(context: Context, reason: ExternalRejection, file: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.external_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
        val reasonText = context.getString(reasonResource(reason))
        val text = if (file == null) reasonText else context.getString(R.string.external_rejected_book, file, reasonText)
        val target = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (reason == ExternalRejection.DISABLED) target.putExtra(OpenNotifications.EXTRA_MORE_TARGET, MoreTarget.EXTERNAL_CONTROL)
        val intent = PendingIntent.getActivity(context, NOTIFICATION, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.external_rejected_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        if (OpenNotifications.allowed(context)) manager.notify(NOTIFICATION, notification)
    }

    fun reasonResource(reason: ExternalRejection) = when (reason) {
        ExternalRejection.DISABLED -> R.string.external_rejected_disabled
        ExternalRejection.UNREGISTERED -> R.string.external_rejected_unregistered
        ExternalRejection.UNKNOWN_OPERATION -> R.string.external_rejected_unknown
        ExternalRejection.MALFORMED -> R.string.external_rejected_malformed
        ExternalRejection.NO_LIBRARY -> R.string.external_rejected_no_library
        ExternalRejection.NOT_OPENED -> R.string.external_rejected_not_opened
        ExternalRejection.OTHER_LIBRARY -> R.string.external_rejected_other_library
        ExternalRejection.NOT_IN_LIBRARY -> R.string.external_rejected_not_in_library
        ExternalRejection.NO_READ_COLUMN -> R.string.external_rejected_no_column
        ExternalRejection.NOT_WRITABLE -> R.string.external_rejected_not_writable
    }
}
