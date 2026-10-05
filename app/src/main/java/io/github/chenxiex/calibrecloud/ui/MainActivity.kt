package io.github.chenxiex.calibrecloud.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.chenxiex.calibrecloud.R

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White, onBackground = Color.Black)) {
                UnconfiguredLibrary()
            }
        }
    }
}

@Composable
private fun UnconfiguredLibrary() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.library_unconfigured), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.local_authorization), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.local_unavailable))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onedrive_authorization), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.onedrive_unavailable))
    }
}
