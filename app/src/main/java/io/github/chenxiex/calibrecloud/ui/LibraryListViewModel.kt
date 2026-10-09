package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.ConfiguredLibrary
import io.github.chenxiex.calibrecloud.state.LibraryAddition
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.storage.cache.CleanupPlan
import io.github.chenxiex.calibrecloud.storage.local.DirectoryGrantResult
import io.github.chenxiex.calibrecloud.storage.local.DirectorySelectionIssue
import io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryAuthorization
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAccess
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorizations
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** A listed library as the 书库 page shows it. */
data class LibraryEntry(val library: ConfiguredLibrary, val access: LibraryAccess, val current: Boolean)

enum class LibraryIssue { UNSUPPORTED_PROVIDER, PERSISTENCE_FAILED, WRONG_DIRECTORY, FAILED }

/** Where the add-library wizard is, derived from the persisted addition and the OneDrive login. */
enum class AdditionStep { TYPE, LOCAL_DIRECTORY, ONEDRIVE_LOGIN, ONEDRIVE_ACCOUNT, ONEDRIVE_DIRECTORY, CONFIRM }

/**
 * The 书库 page and the add-library wizard (R03, R30). The list and the addition live in the state
 * database, so both survive process death; this model only serializes the operations on them. Local
 * directory grants are released once neither a listed library nor the addition uses them. Switching
 * never asks for confirmation; deleting shows the exact range first (Q59).
 */
class LibraryListViewModel(
    private val state: ApplicationStateRepository,
    private val local: LocalDirectoryAuthorization,
    private val authorizations: LibraryAuthorizations,
    private val maintenance: CacheMaintenance,
    private val session: suspend () -> UUID?,
    private val requestSync: suspend () -> TaskId?,
    private val wake: suspend () -> Unit,
    /** Lets work that waited for a directory grant continue. */
    private val onAuthorized: suspend () -> Unit,
) : ViewModel() {
    /** null until first read. */
    var entries by mutableStateOf<List<LibraryEntry>?>(null)
        private set
    var addition by mutableStateOf<LibraryAddition?>(null)
        private set
    var issue by mutableStateOf<LibraryIssue?>(null)
        private set
    var removal by mutableStateOf<CleanupPlan?>(null)
        private set
    /** Increases whenever the current library may have changed, so other pages reread it. */
    var revision by mutableIntStateOf(0)
        private set
    private var pending by mutableIntStateOf(0)
    val busy: Boolean get() = pending > 0
    private var operation: Job? = null
    /** The listed location a directory pick re-authorizes; null when the pick is for the addition. */
    private var reauthorizing: LibraryLocation? = null
    /** Set when the wizard asked for a login, so the login that follows confirms the account. */
    private var loginForAddition = false

    fun refresh() = enqueue(clearIssue = false) { }

    fun switchTo(location: LibraryLocation) = enqueue {
        state.switchTo(location) ?: run { issue = LibraryIssue.FAILED; return@enqueue }
        revision++
    }

    /** Opens the wizard's next step for [backend]; replaces an unfinished addition. */
    fun startAddition(backend: BackendKind) = enqueue {
        val (_, previous) = state.beginAddition(backend, null)
        previous?.accessKey?.let { releaseUnused(it) }
    }

    /** The next directory pick re-authorizes [location]; null makes it the addition's root. */
    fun pickFor(location: LibraryLocation?) {
        reauthorizing = location
    }

    /** A system directory picker result; null [treeUri] is a cancelled pick. */
    fun picked(treeUri: String?, resultFlags: Int) = enqueue {
        val target = reauthorizing
        reauthorizing = null
        when (val result = local.grant(treeUri, resultFlags) { state.accessKeyInUse(it) }) {
            DirectoryGrantResult.Cancelled -> Unit
            is DirectoryGrantResult.Rejected -> issue = when (result.issue) {
                DirectorySelectionIssue.UNSUPPORTED_PROVIDER -> LibraryIssue.UNSUPPORTED_PROVIDER
                DirectorySelectionIssue.PERSISTENCE_FAILED -> LibraryIssue.PERSISTENCE_FAILED
            }
            is DirectoryGrantResult.Granted -> if (target != null) {
                if (result.location != target) {
                    issue = LibraryIssue.WRONG_DIRECTORY
                    releaseUnused(result.treeUri)
                } else {
                    val old = state.replaceAccessKey(target, result.treeUri).getOrNull()
                    if (old != null && old != result.treeUri) releaseUnused(old)
                    onAuthorized()
                }
            } else {
                val current = state.addition()?.takeIf { it.backend == BackendKind.LOCAL }
                if (current == null) {
                    releaseUnused(result.treeUri)
                } else {
                    state.chooseAddition(current.token, result.location, result.displayName, result.treeUri).getOrNull()
                        ?.let { releaseUnused(it) }
                }
            }
        }
    }

    /** Continues the OneDrive addition with the account that is signed in now (Q61). */
    fun useCurrentAccount() = enqueue {
        val signedIn = session() ?: return@enqueue
        if (state.addition()?.backend == BackendKind.ONEDRIVE) state.beginAddition(BackendKind.ONEDRIVE, signedIn)
    }

    fun loginRequested() {
        loginForAddition = true
    }

    /** After a login the wizard asked for, the new account is the one the addition uses. */
    fun signedIn() {
        if (!loginForAddition) return
        enqueue {
            val signedIn = session() ?: return@enqueue
            loginForAddition = false
            if (state.addition()?.backend == BackendKind.ONEDRIVE) state.beginAddition(BackendKind.ONEDRIVE, signedIn)
        }
    }

    /**
     * One wizard step back: from the confirmation to choosing a directory, otherwise to choosing the
     * type, which abandons the addition. On the type step there is nothing to undo, so [onFirstStep]
     * leaves the wizard.
     */
    fun back(onFirstStep: () -> Unit) {
        if (addition == null && !busy) return onFirstStep()
        enqueue {
            val current = state.addition() ?: return@enqueue
            if (current.location != null) {
                state.clearAdditionRoot(current.token)
                current.accessKey?.let { releaseUnused(it) }
            } else {
                state.cancelAddition()?.accessKey?.let { releaseUnused(it) }
            }
        }
    }

    /** Lists the chosen library, makes it current and syncs it at user priority; [onDone] then shows the library. */
    fun complete(onDone: () -> Unit) = enqueue {
        val token = state.addition()?.token ?: return@enqueue
        val (_, replaced) = state.completeAddition(token) ?: run { issue = LibraryIssue.FAILED; return@enqueue }
        replaced?.let { releaseUnused(it) }
        revision++
        requestSync()
        wake()
        onDone()
    }

    fun previewRemoval(location: LibraryLocation) = enqueue {
        removal = maintenance.previewLibrary(location) ?: run { issue = LibraryIssue.FAILED; null }
    }

    fun cancelRemoval() {
        if (!busy) removal = null
    }

    fun confirmRemoval() {
        val plan = removal ?: return
        enqueue {
            val key = plan.location?.let { state.accessKey(it) }
            val removed = maintenance.execute(plan)
            removal = null
            if (!removed) issue = LibraryIssue.FAILED
            else key?.let { releaseUnused(it) }
            revision++
        }
    }

    private suspend fun releaseUnused(accessKey: String) {
        if (!state.accessKeyInUse(accessKey)) local.release(accessKey)
    }

    /** Operations run one at a time; every one ends by rereading the list and the addition. */
    private fun enqueue(clearIssue: Boolean = true, action: suspend () -> Unit) {
        val previous = operation
        pending++
        operation = viewModelScope.launch {
            try {
                previous?.join()
                if (clearIssue) issue = null
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                issue = LibraryIssue.FAILED
            } finally {
                try {
                    reload()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    issue = LibraryIssue.FAILED
                }
                pending--
            }
        }
    }

    private suspend fun reload() {
        val current = state.current()?.location
        entries = state.libraries().map { library ->
            LibraryEntry(library, authorizations.of(library.location.backend).access(library.location, library.accessKey),
                library.location == current)
        }
        addition = state.addition()
    }

    companion object {
        /** The step [addition] is at, given whether OneDrive is signed in. */
        fun step(addition: LibraryAddition?, signedIn: Boolean): AdditionStep = when {
            addition == null -> AdditionStep.TYPE
            addition.location != null -> AdditionStep.CONFIRM
            addition.backend == BackendKind.LOCAL -> AdditionStep.LOCAL_DIRECTORY
            !signedIn -> AdditionStep.ONEDRIVE_LOGIN
            addition.authorizationId == null -> AdditionStep.ONEDRIVE_ACCOUNT
            else -> AdditionStep.ONEDRIVE_DIRECTORY
        }

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(LibraryListViewModel(dependencies.state, dependencies.localAuthorization,
                    dependencies.libraryAuthorizations, dependencies.maintenance, { dependencies.oneDriveAuthorization.sessionId() },
                    { dependencies.librarySync.request(TaskOrigin.MANUAL_SYNC) }, { dependencies.taskCoordinator.requestRun() },
                    dependencies.backgroundTasks::resumeAuthorizationWaits))!!
            }
        }
    }
}
