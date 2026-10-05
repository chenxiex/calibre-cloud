package io.github.chenxiex.calibrecloud.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationState
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus
import io.github.chenxiex.calibrecloud.storage.local.DirectorySelectionIssue

class MainActivity : ComponentActivity() {
    private val authorizationModel by lazy {
        ViewModelProvider(this, LocalDirectoryAuthorizationViewModel.factory(applicationContext))[
            LocalDirectoryAuthorizationViewModel::class.java,
        ]
    }
    private var pickerOpen by mutableStateOf(false)
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerOpen = false
        authorizationModel.select(
            if (result.resultCode == RESULT_OK) result.data?.data?.toString() else null,
            result.data?.flags ?: 0,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerOpen = savedInstanceState?.getBoolean("picker_open") ?: false
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White, onBackground = Color.Black)) {
                AuthorizationPage(authorizationModel.state, authorizationModel.busy || pickerOpen) {
                    pickerOpen = true
                    picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    ))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pickerOpen) return
        authorizationModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker_open", pickerOpen)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun AuthorizationPage(state: DirectoryAuthorizationState, busy: Boolean, onSelect: () -> Unit) {
    // Split authorization entries into explicit pages without scrolling or animated controls.
    var page by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White)
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f)) {
            if (page == 0) {
                Text(stringResource(R.string.local_authorization), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (state.status) {
                    DirectoryAuthorizationStatus.UNSELECTED -> R.string.local_unselected
                    DirectoryAuthorizationStatus.AUTHORIZED -> R.string.local_authorized
                    DirectoryAuthorizationStatus.READ_ONLY -> R.string.local_read_only
                    DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED -> R.string.local_reauthorize
                    DirectoryAuthorizationStatus.UNSUPPORTED_PROVIDER -> R.string.local_unsupported
                }))
                state.selectionIssue?.let {
                    Text(stringResource(when (it) {
                        DirectorySelectionIssue.UNSUPPORTED_PROVIDER -> R.string.local_unsupported
                        DirectorySelectionIssue.PERSISTENCE_FAILED -> R.string.local_persistence_failed
                        DirectorySelectionIssue.CONFIGURATION_FAILED -> R.string.local_configuration_failed
                    }))
                }
                Spacer(Modifier.height(16.dp))
                StaticButton(stringResource(if (busy) R.string.local_checking else R.string.local_select), !busy, onSelect)
            } else {
                Text(stringResource(R.string.onedrive_authorization), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.onedrive_unavailable))
            }
        }
        StaticButton(stringResource(if (page == 0) R.string.next_page else R.string.previous_page), true) { page = 1 - page }
    }
}

@Composable
private fun StaticButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, Color.Black).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = enabled, onClick = onClick,
    ).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label)
    }
}
