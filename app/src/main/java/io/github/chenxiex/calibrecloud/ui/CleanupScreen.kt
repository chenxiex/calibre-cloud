package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.storage.cache.CleanupKind

/** Range review uses explicit pages, with no scrolling, loading animation, or source request. */
@Composable
internal fun CleanupScreen(model: CleanupViewModel) {
    Text(stringResource(R.string.cleanup_title), style = MaterialTheme.typography.titleMedium)
    val plan = model.plan
    if (plan == null) {
        Text(stringResource(R.string.cleanup_explanation))
        Spacer(Modifier.height(12.dp))
        StaticButton(stringResource(R.string.cleanup_metadata), !model.busy) { model.preview(CleanupKind.METADATA) }
        Spacer(Modifier.height(8.dp))
        StaticButton(stringResource(R.string.cleanup_other_libraries), !model.busy) { model.preview(CleanupKind.OTHER_LIBRARIES) }
    } else {
        Text(stringResource(if (plan.kind == CleanupKind.METADATA) R.string.cleanup_metadata_scope
            else R.string.cleanup_other_libraries_scope))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cleanup_range, plan.libraries.size, plan.copies.size, plan.bytes))
        val libraries = plan.libraries.sortedBy { it.value.toString() }
        var libraryPage by rememberSaveable(plan.selectionToken.toString(), plan.kind.name) { mutableIntStateOf(0) }
        val currentPage = libraryPage.coerceIn(0, maxOf(0, libraries.lastIndex))
        libraries.getOrNull(currentPage)?.let { library ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cleanup_library, library.value.toString()))
            Text(stringResource(R.string.cleanup_library_page, currentPage + 1, libraries.size))
            Row {
                StaticButton(stringResource(R.string.page_previous), !model.busy && currentPage > 0) { libraryPage = currentPage - 1 }
                Spacer(Modifier.width(8.dp))
                StaticButton(stringResource(R.string.page_next), !model.busy && currentPage + 1 < libraries.size) { libraryPage = currentPage + 1 }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            StaticButton(stringResource(R.string.cleanup_confirm), !model.busy) { model.confirm() }
            Spacer(Modifier.width(8.dp))
            StaticButton(stringResource(R.string.cleanup_cancel), !model.busy) { model.cancel() }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (model.busy) Text(stringResource(R.string.cleanup_busy))
    model.result?.let { result ->
        Text(stringResource(when (result) {
            CleanupResult.COMPLETED -> R.string.cleanup_completed
            CleanupResult.FAILED -> R.string.cleanup_failed
            CleanupResult.UNAVAILABLE -> R.string.cleanup_unavailable
        }))
    }
}
