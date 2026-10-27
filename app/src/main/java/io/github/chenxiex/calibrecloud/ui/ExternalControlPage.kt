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
import io.github.chenxiex.calibrecloud.control.InstallResult
import io.github.chenxiex.calibrecloud.control.KoreaderPluginInstaller
import io.github.chenxiex.calibrecloud.control.KoreaderPluginNotifications
import io.github.chenxiex.calibrecloud.control.PluginStatus
import io.github.chenxiex.calibrecloud.state.ExternalClient
import io.github.chenxiex.calibrecloud.state.ExternalControlStore
import io.github.chenxiex.calibrecloud.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The external reader control settings (R37, Q92, Q97): the switch, the registered callers and the KOReader plugin. */
class ExternalControlViewModel(
    private val store: ExternalControlStore,
    private val plugin: KoreaderPluginInstaller,
    /** Withdraws the launch notice that the plugin needs an update (Q98). */
    private val updated: () -> Unit,
) : ViewModel() {
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

    /** Null until first read. */
    var pluginStatus by mutableStateOf<PluginStatus?>(null)
        private set
    var bundledVersion by mutableStateOf("")
        private set
    /** The last install's outcome, shown until the page is left. */
    var pluginResult by mutableStateOf<InstallResult?>(null)
        private set
    var pluginBusy by mutableStateOf(false)
        private set

    fun loadPlugin() = pluginOperation { null }

    /** Installs into the saved folder; a status without one asks for the picker instead. */
    fun installPlugin() = pluginOperation { plugin.install() }

    /** The picker's result; nothing happens when it was cancelled. */
    fun pluginPicked(treeUri: String?, resultFlags: Int) {
        // Never dropped: the installer runs one operation at a time anyway, and the grant is only offered now.
        if (treeUri != null) pluginOperation(always = true) { plugin.choose(treeUri, resultFlags) }
    }

    fun clearPluginResult() {
        pluginResult = null
    }

    private fun pluginOperation(always: Boolean = false, install: suspend () -> InstallResult?) {
        if (pluginBusy && !always) return
        pluginBusy = true
        viewModelScope.launch {
            try {
                install()?.let { result ->
                    pluginResult = result
                    if (result == InstallResult.INSTALLED) updated()
                }
                bundledVersion = plugin.bundledVersion()
                pluginStatus = plugin.status()
            } finally {
                pluginBusy = false
            }
        }
    }

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
                val application = context.applicationContext
                return modelClass.cast(ExternalControlViewModel(dependencies.externalControl, dependencies.koreaderPlugin) {
                    KoreaderPluginNotifications.withdraw(application)
                })!!
            }
        }
    }
}

/**
 * The switch, then the registered callers by name and registration time; tapping one confirms its
 * removal. Registration itself only happens through a reader's request and its confirmation. Last the
 * KOReader plugin's status and install (Q97, Q98); [selectFolder] opens the system folder picker.
 */
@Composable
internal fun ExternalControlPage(model: ExternalControlViewModel, selectFolder: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(model) {
        model.clearPluginResult()
        model.load()
        model.loadPlugin()
    }
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
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.koreader_plugin_heading)))
        model.pluginStatus?.let { status ->
            val unknown = stringResource(R.string.koreader_plugin_unknown_version)
            val label = when (status) {
                PluginStatus.NoFolder, PluginStatus.NotInstalled -> stringResource(R.string.koreader_plugin_not_installed)
                is PluginStatus.Installed -> if (status.current) stringResource(R.string.koreader_plugin_installed, status.version ?: unknown)
                    else stringResource(R.string.koreader_plugin_outdated, status.version ?: unknown, model.bundledVersion)
            }
            val (icon, description) = when {
                status !is PluginStatus.Installed -> R.drawable.ic_download to R.string.koreader_plugin_install
                status.current -> R.drawable.ic_refresh to R.string.koreader_plugin_reinstall
                else -> R.drawable.ic_download to R.string.koreader_plugin_update
            }
            add(MenuEntry.Actions("koreader_plugin_status", label, buildList {
                add(RowAction("koreader_plugin_install", icon, stringResource(description), !model.pluginBusy) {
                    if (status == PluginStatus.NoFolder) selectFolder() else model.installPlugin()
                })
                if (status != PluginStatus.NoFolder) {
                    add(RowAction("koreader_plugin_choose", R.drawable.ic_folder, stringResource(R.string.koreader_plugin_choose),
                        !model.pluginBusy, selectFolder))
                }
            }))
            val note = when (model.pluginResult) {
                InstallResult.INSTALLED -> R.string.koreader_plugin_done
                InstallResult.NO_FOLDER -> R.string.koreader_plugin_folder_lost
                InstallResult.NOT_KOREADER -> R.string.koreader_plugin_not_koreader
                InstallResult.FAILED -> R.string.koreader_plugin_failed
                null -> R.string.koreader_plugin_install_hint.takeIf { status == PluginStatus.NoFolder }
            }
            note?.let { add(MenuEntry.Note("koreader_plugin_result", stringResource(it))) }
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
