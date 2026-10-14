package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.state.ExternalClient
import io.github.chenxiex.calibrecloud.state.ExternalControlStore
import io.github.chenxiex.calibrecloud.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The external reader control settings (R37, Q92): the switch and the registered callers. */
class ExternalControlViewModel(private val store: ExternalControlStore) : ViewModel() {
    var loaded by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(false)
        private set
    var clients by mutableStateOf(emptyList<ExternalClient>())
        private set
    var failed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    /** Rereads the settings; a registration confirmed over a reader changes them while this page is away. */
    fun load() = update { }

    fun toggle() = update { store.setEnabled(!enabled) }

    fun remove(name: String) = update { store.remove(name) }

    /** Applies [change] (none to only reread) and rereads; operations run one at a time. */
    private fun update(change: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                change()
                enabled = store.enabled()
                clients = store.clients()
                loaded = true
                failed = false
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(ExternalControlViewModel(dependencies.externalControl))!!
            }
        }
    }
}

/**
 * The switch, then the registered callers by name and registration time; tapping one confirms its
 * removal. Registration itself only happens through a reader's request and its confirmation.
 */
@Composable
internal fun ExternalControlPage(model: ExternalControlViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.load() }
    val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val entries = buildList {
        add(MenuEntry.Note("external_control_explanation", stringResource(R.string.external_control_explanation)))
        if (model.failed) add(MenuEntry.Note("external_control_failed", stringResource(R.string.external_control_failed)))
        add(MenuEntry.Choice("external_control_switch", stringResource(R.string.external_control_switch), selected = model.enabled,
            multiple = true, enabled = model.loaded && !model.busy) { model.toggle() })
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.external_control_clients)))
        if (model.loaded && model.clients.isEmpty()) {
            add(MenuEntry.Note("external_control_no_clients", stringResource(R.string.external_control_no_clients)))
        }
        model.clients.forEachIndexed { index, client ->
            add(MenuEntry.Choice("external_client_$index", client.name, enabled = !model.busy,
                supporting = stringResource(R.string.external_control_registered_at, format.format(Date(client.registeredAt)))) {
                removing = client.name
            })
        }
    }
    Box(Modifier.fillMaxSize()) {
        PagedEntries(entries, page, { page = it }, Modifier.testTag("external_control_page"), "external_control")
        removing?.let { name ->
            ConfirmPanel("external_client_remove", !model.busy, { removing = null }, {
                model.remove(name)
                removing = null
            }, title = stringResource(R.string.external_control_remove_title, name)) {
                Text(stringResource(R.string.external_control_remove_text))
            }
        }
    }
}
