package io.github.chenxiex.calibrecloud.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val librariesModel by lazy {
        ViewModelProvider(this, LibraryListViewModel.factory(applicationContext))[LibraryListViewModel::class.java]
    }
    private val oneDriveModel by lazy {
        ViewModelProvider(this, OneDriveAuthorizationViewModel.factory(applicationContext))[
            OneDriveAuthorizationViewModel::class.java,
        ]
    }
    private val oneDriveLibraryModel by lazy {
        ViewModelProvider(this, OneDriveLibraryViewModel.factory(applicationContext))[OneDriveLibraryViewModel::class.java]
    }
    private val metadataModel by lazy {
        ViewModelProvider(this, MetadataViewModel.factory(applicationContext))[MetadataViewModel::class.java]
    }
    private val downloadModel by lazy {
        ViewModelProvider(this, DownloadViewModel.factory(applicationContext))[DownloadViewModel::class.java]
    }
    private val cleanupModel by lazy {
        ViewModelProvider(this, CleanupViewModel.factory(applicationContext))[CleanupViewModel::class.java]
    }
    private val libraryModel by lazy {
        ViewModelProvider(this, LibraryViewModel.factory(applicationContext))[LibraryViewModel::class.java]
    }
    private val openModel by lazy {
        ViewModelProvider(this, OpenViewModel.factory(applicationContext))[OpenViewModel::class.java]
    }
    private val taskModel by lazy {
        ViewModelProvider(this, TaskViewModel.factory(applicationContext))[TaskViewModel::class.java]
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        taskModel.refreshCapabilities()
        pendingNotice?.invoke()
        pendingNotice = null
    }
    /** A notice held back while the notification permission is asked for. */
    private var pendingNotice: (() -> Unit)? = null
    /** The [MoreTarget] a tapped open notification asks for. */
    private var moreRequest by mutableStateOf<Int?>(null)
    private var pickerOpen by mutableStateOf(false)
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerOpen = false
        librariesModel.picked(
            if (result.resultCode == RESULT_OK) result.data?.data?.toString() else null,
            result.data?.flags ?: 0,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerOpen = savedInstanceState?.getBoolean("picker_open") ?: false
        intent.data?.toString()?.let { oneDriveModel.callback(it); intent.data = null }
        if (savedInstanceState == null) takeMoreRequest(intent)
        val dependencies = (application as io.github.chenxiex.calibrecloud.CalibreCloudApplication).dependencies
        dependencies.applicationScope.launch { dependencies.backgroundTasks.onMainOpened() }
        enableEdgeToEdge()
        setContent {
            CalibreCloudTheme {
                val models = MoreModels(librariesModel, oneDriveModel, oneDriveLibraryModel, metadataModel, downloadModel,
                    cleanupModel, taskModel, openModel)
                val actions = MoreActions(
                    selectDirectory = {
                        pickerOpen = true
                        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                        ))
                    },
                    login = { chooseAccount -> oneDriveModel.login(chooseAccount) { startActivity(it) } },
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
                MainScreen(libraryModel, openModel, { launchReader(this, it.copy) }, ::notify, ::notifyBatch, moreRequest, { moreRequest = null },
                    librariesModel.revision) { request, handled, showLibrary ->
                    MoreScreen(models, actions, pickerOpen, request, handled, showLibrary)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { oneDriveModel.callback(it) }
        intent.data = null
        takeMoreRequest(intent)
    }

    private fun takeMoreRequest(intent: Intent) {
        if (!intent.hasExtra(OpenNotifications.EXTRA_MORE_TARGET)) return
        moreRequest = intent.getIntExtra(OpenNotifications.EXTRA_MORE_TARGET, 0)
        intent.removeExtra(OpenNotifications.EXTRA_MORE_TARGET)
    }

    /** Posts or withdraws the open notice; the first notice asks for the permission it needs. */
    private fun notify(notice: OpenNotice) {
        val status = notice.status
        if (status == null) {
            OpenNotifications.withdraw(this)
            return
        }
        withPermission { OpenNotifications.post(this, status) }
    }

    private fun notifyBatch(notice: BatchNotice) = withPermission { BatchNotifications.post(this, notice) }

    /** Posts now, or after the permission prompt when the first notification still needs it. */
    private fun withPermission(post: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotice = post
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        post()
    }

    override fun onStart() {
        super.onStart()
        openModel.setForeground(true)
        (application as io.github.chenxiex.calibrecloud.CalibreCloudApplication).dependencies.backgroundTasks.setForeground(true)
    }

    /** Only leaving the foreground revokes a waiting open; a configuration change keeps it. */
    override fun onStop() {
        if (!isChangingConfigurations) {
            openModel.setForeground(false)
            (application as io.github.chenxiex.calibrecloud.CalibreCloudApplication).dependencies.backgroundTasks.setForeground(false)
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        openModel.refresh()
        oneDriveModel.restore()
        oneDriveLibraryModel.restore()
        metadataModel.restore()
        downloadModel.restore()
        libraryModel.refresh()
        if (pickerOpen) return
        librariesModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker_open", pickerOpen)
        super.onSaveInstanceState(outState)
    }
}
