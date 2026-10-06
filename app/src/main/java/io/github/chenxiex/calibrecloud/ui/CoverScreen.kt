package io.github.chenxiex.calibrecloud.ui

import android.graphics.Bitmap

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl

/** One visible book per static page; a title remains available while loading or offline. */
@Composable
internal fun CoverScreen(model: CoverViewModel) {
    Text(stringResource(R.string.cover_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.cover_explanation), maxLines = 2, overflow = TextOverflow.Ellipsis)
    val option = model.books.getOrNull(model.page)
    if (model.selection?.identity == null) {
        Text(stringResource(R.string.metadata_unvalidated))
    } else if (option == null) {
        Text(stringResource(R.string.cover_empty))
    } else {
        Spacer(Modifier.height(8.dp))
        Text(option.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        CoverImage(model.image, option.title)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cover_page, model.page + 1, model.books.size))
        Row {
            StaticButton(stringResource(R.string.page_previous), model.page > 0) { model.paginate(model.page - 1) }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.page_next), model.page + 1 < model.books.size) { model.paginate(model.page + 1) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(when {
        model.failed -> R.string.cover_failed
        model.image != null -> R.string.cover_available
        else -> coverTaskStatusResource(model.record?.state, model.busy)
    }), maxLines = 2, overflow = TextOverflow.Ellipsis)
    model.record?.controls?.let { controls ->
        Spacer(Modifier.height(8.dp))
        Row {
            if (controls.canResume) StaticButton(stringResource(R.string.local_snapshot_resume), !model.busy) { model.control(TaskControl.RESUME) }
            if (controls.canRetry) StaticButton(stringResource(R.string.local_snapshot_retry), !model.busy) { model.control(TaskControl.RETRY) }
            if (controls.canPause) StaticButton(stringResource(R.string.local_snapshot_pause), true) { model.control(TaskControl.PAUSE) }
            if (controls.canCancel) {
                Spacer(Modifier.width(8.dp))
                StaticButton(stringResource(R.string.onedrive_task_cancel), true) { model.control(TaskControl.CANCEL) }
            }
        }
        if (model.record?.state == TaskState.Queued || model.record?.state is TaskState.Waiting) {
            StaticButton(stringResource(R.string.local_snapshot_run), !model.busy) { model.runQueued() }
        }
    }
    Spacer(Modifier.height(8.dp))
    StaticButton(stringResource(R.string.metadata_refresh_local), !model.busy) { model.restore() }
}

@Composable
private fun CoverImage(bitmap: Bitmap?, title: String) {
    Box(Modifier.size(144.dp, 192.dp).border(1.dp, Color.Black), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text(title, maxLines = 6, overflow = TextOverflow.Ellipsis)
        } else {
            Image(bitmap.asImageBitmap(), title, Modifier.size(144.dp, 192.dp), contentScale = ContentScale.Fit)
        }
    }
}

private fun coverTaskStatusResource(state: TaskState?, busy: Boolean): Int = when (state) {
    null -> if (busy) R.string.cover_reading else R.string.cover_placeholder
    TaskState.Queued -> R.string.cover_queued
    is TaskState.Running -> R.string.cover_running
    is TaskState.Waiting -> R.string.cover_waiting
    is TaskState.Paused -> R.string.cover_paused
    is TaskState.Finished -> when (state.result) {
        is TaskResult.Cancelled -> R.string.cover_cancelled
        TaskResult.Completed -> R.string.cover_placeholder
        else -> R.string.cover_failed
    }
}
