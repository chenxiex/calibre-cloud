package io.github.chenxiex.calibrecloud.ui

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.auth.LoginIssue
import io.github.chenxiex.calibrecloud.auth.LoginStatus
import io.github.chenxiex.calibrecloud.auth.OneDriveOAuthConfiguration
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.api.LocationKeys
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAccess

/** Stable test tag of a listed library, without exposing its location. */
internal fun libraryTag(location: LibraryLocation): String =
    "library_" + Integer.toHexString(LocationKeys.encode(location).hashCode())

private fun backendName(backend: BackendKind) = when (backend) {
    BackendKind.LOCAL -> R.string.more_location_local
    BackendKind.ONEDRIVE -> R.string.more_location_onedrive
}

/** The 书库 page's top bar: back, the title and + for [onAdd], which opens the wizard. */
@Composable
internal fun LibrariesTopBar(model: LibraryListViewModel, onBack: () -> Unit, onAdd: () -> Unit) =
    MoreTopBar(stringResource(R.string.more_libraries), onBack) {
        IconAction(R.drawable.ic_add, stringResource(R.string.libraries_add), !model.busy, Modifier.testTag("libraries_add"), onClick = onAdd)
    }

/**
 * The 书库 page (R03, R30): the listed libraries, as many per page as fit, the current one checked.
 * Tapping another library switches to it; a library that lost its grant or login offers re-authorizing;
 * deleting confirms the exact range first. Adding is the + in [LibrariesTopBar].
 */
@Composable
internal fun LibrariesPage(model: LibraryListViewModel, oneDrive: OneDriveAuthorizationViewModel, actions: MoreActions) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().testTag("libraries_page")) {
        Column(Modifier.fillMaxSize()) {
            model.issue?.let { Text(stringResource(issueText(it)), Modifier.padding(PAGE_MARGIN, SECTION_GAP).testTag("libraries_issue")) }
            // A re-login started from a row reports here while it runs or fails; the wizard shows its own.
            // Being signed in needs nothing from the user, so it shows nothing even beside a transient refresh failure.
            if (oneDrive.status != LoginStatus.AUTHORIZED &&
                (oneDrive.issue != null || oneDrive.status == LoginStatus.BROWSER || oneDrive.status == LoginStatus.EXCHANGING)) {
                Column(Modifier.padding(PAGE_MARGIN, SECTION_GAP), verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                    LoginState(oneDrive)
                    if (oneDrive.status == LoginStatus.BROWSER) {
                        ActionButton(stringResource(R.string.onedrive_cancel), !oneDrive.busy, Modifier.testTag("onedrive_cancel"), ButtonKind.TEXT) {
                            oneDrive.cancel()
                        }
                    }
                }
                HorizontalRule()
            }
            val entries = model.entries.orEmpty()
            if (model.entries != null && entries.isEmpty()) {
                EmptyMessage(stringResource(R.string.libraries_empty), Modifier.testTag("libraries_empty"))
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val rows = listGeometry(maxWidth.value, (maxHeight - PAGE_BAR_HEIGHT).value, TWO_LINE_ROW_HEIGHT.value).rows.coerceAtLeast(1)
                    val pages = pageCount(entries.size, rows)
                    val current = page.coerceIn(0, pages - 1)
                    PagedArea(current, pages, "libraries", { page = it }, Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            entries.drop(current * rows).take(rows).forEach { LibraryRow(it, model, actions) }
                        }
                    }
                }
            }
        }
        model.removal?.let { plan ->
            val name = model.entries?.firstOrNull { it.library.location == plan.location }?.library?.displayName
                ?: stringResource(R.string.libraries_unnamed)
            ConfirmPanel("library_delete_dialog", !model.busy, model::cancelRemoval, model::confirmRemoval,
                stringResource(R.string.libraries_delete_title, name)) {
                Text(stringResource(R.string.libraries_delete_scope, plan.copies.size, Formatter.formatShortFileSize(context, plan.bytes)))
                if (plan.deletesCurrent) Text(stringResource(R.string.libraries_delete_current))
            }
        }
    }
}

private fun issueText(issue: LibraryIssue) = when (issue) {
    LibraryIssue.UNSUPPORTED_PROVIDER -> R.string.local_unsupported
    LibraryIssue.PERSISTENCE_FAILED -> R.string.local_persistence_failed
    LibraryIssue.WRONG_DIRECTORY -> R.string.libraries_issue_wrong_directory
    LibraryIssue.FAILED -> R.string.libraries_issue_failed
}

/**
 * The current library's radio is chosen and its name bold (Q65); name over backend and state, then
 * re-authorize and remove. The row body switches to the library.
 */
@Composable
private fun LibraryRow(entry: LibraryEntry, model: LibraryListViewModel, actions: MoreActions) {
    val location = entry.library.location
    val tag = libraryTag(location)
    val name = entry.library.displayName ?: stringResource(R.string.libraries_unnamed)
    val enabled = !model.busy
    val currentDescription = stringResource(R.string.libraries_current)
    ListItem(
        name, Modifier.testTag(tag), TWO_LINE_ROW_HEIGHT, enabled, bold = entry.current, divider = true,
        // The current row has no switch at all, so it is not a disabled container around its enabled actions.
        onClick = if (entry.current) null else ({ model.switchTo(location) }),
        leading = {
            SelectionMark(entry.current, multiple = false,
                if (entry.current) Modifier.testTag("${tag}_current").semantics { contentDescription = currentDescription } else Modifier)
        },
        supporting = {
            SupportingText(stringResource(R.string.libraries_status, stringResource(backendName(location.backend)), stringResource(accessText(entry))),
                Modifier.testTag("${tag}_status"))
        },
    ) {
        if (entry.access == LibraryAccess.REAUTHORIZE) {
            ActionButton(stringResource(R.string.libraries_reauthorize), enabled, Modifier.testTag("${tag}_reauthorize")) {
                when (location.backend) {
                    BackendKind.LOCAL -> {
                        model.pickFor(location)
                        actions.selectDirectory()
                    }
                    // The login page lets the user pick this library's account (Q61).
                    BackendKind.ONEDRIVE -> actions.login(true)
                }
            }
        }
        IconAction(R.drawable.ic_delete, stringResource(R.string.libraries_delete, name), enabled, Modifier.testTag("${tag}_delete")) {
            model.previewRemoval(location)
        }
    }
}

private fun accessText(entry: LibraryEntry) = when (entry.access) {
    LibraryAccess.READY -> if (entry.library.location.backend == BackendKind.LOCAL) R.string.libraries_ready_local else R.string.libraries_ready_onedrive
    LibraryAccess.READ_ONLY -> R.string.libraries_read_only
    LibraryAccess.REAUTHORIZE ->
        if (entry.library.location.backend == BackendKind.LOCAL) R.string.libraries_reauthorize_local else R.string.libraries_reauthorize_onedrive
}

/**
 * The wizard's top bar: the step and its progress as the title. Back ([onBack]) cancels on the first
 * step and goes one step back after it, as its description says; the last step adds 完成, which calls
 * [onDone] once the library is listed, current and syncing.
 */
@Composable
internal fun AddLibraryTopBar(
    model: LibraryListViewModel, oneDrive: OneDriveAuthorizationViewModel, pickerOpen: Boolean, onBack: () -> Unit, onDone: () -> Unit,
) {
    val step = LibraryListViewModel.step(model.addition, oneDrive.status == LoginStatus.AUTHORIZED)
    val (number, title) = when (step) {
        AdditionStep.TYPE -> 1 to R.string.wizard_step_type
        AdditionStep.CONFIRM -> 3 to R.string.wizard_step_confirm
        else -> 2 to R.string.wizard_step_directory
    }
    MoreTopBar(stringResource(R.string.wizard_step, number, stringResource(title)), onBack,
        stringResource(if (step == AdditionStep.TYPE) R.string.wizard_cancel else R.string.wizard_previous)) {
        if (step == AdditionStep.CONFIRM) {
            ActionButton(stringResource(R.string.wizard_complete), !model.busy && !pickerOpen, Modifier.testTag("wizard_complete"), ButtonKind.TEXT) {
                model.complete(onDone)
            }
        }
    }
}

/**
 * The add-library wizard (R30, Q58–Q61): choose the type, authorize and choose the directory, confirm.
 * The step follows the persisted addition, so it survives the system picker, the browser login and
 * process death. Navigation and 完成 are in [AddLibraryTopBar]; nothing changes the library list or the
 * current library before 完成.
 */
@Composable
internal fun AddLibraryPage(
    model: LibraryListViewModel, oneDrive: OneDriveAuthorizationViewModel, directories: OneDriveLibraryViewModel,
    actions: MoreActions, pickerOpen: Boolean,
) {
    val addition = model.addition
    val signedIn = oneDrive.status == LoginStatus.AUTHORIZED
    val step = LibraryListViewModel.step(addition, signedIn)
    val busy = model.busy || pickerOpen
    LaunchedEffect(signedIn) { if (signedIn) model.signedIn() }
    Box(Modifier.fillMaxSize().testTag("add_library_page")) {
        when (step) {
            AdditionStep.TYPE -> TypeStep(model, busy) { backend ->
                model.startAddition(backend)
                // The picker opens at once; the step offers it again after a cancelled pick.
                if (backend == BackendKind.LOCAL) {
                    model.pickFor(null)
                    actions.selectDirectory()
                }
            }
            AdditionStep.LOCAL_DIRECTORY -> FormColumn("wizard_local") {
                Text(stringResource(R.string.wizard_local_prompt))
                model.issue?.let { Text(stringResource(issueText(it)), Modifier.testTag("wizard_issue")) }
                ActionButton(stringResource(if (busy) R.string.local_checking else R.string.wizard_local_choose), !busy,
                    Modifier.testTag("wizard_local_choose"), ButtonKind.PRIMARY) {
                    model.pickFor(null)
                    actions.selectDirectory()
                }
            }
            AdditionStep.ONEDRIVE_LOGIN -> FormColumn("wizard_onedrive_sign_in") {
                Text(stringResource(R.string.wizard_onedrive_login_prompt))
                LoginState(oneDrive)
                if (oneDrive.status == LoginStatus.BROWSER) {
                    ActionButton(stringResource(R.string.onedrive_cancel), !oneDrive.busy, Modifier.testTag("onedrive_cancel"), ButtonKind.TEXT) {
                        oneDrive.cancel()
                    }
                } else {
                    ActionButton(stringResource(R.string.wizard_onedrive_login), !oneDrive.busy && !busy, Modifier.testTag("wizard_onedrive_login"),
                        ButtonKind.PRIMARY) {
                        model.loginRequested()
                        actions.login(false)
                    }
                }
            }
            AdditionStep.ONEDRIVE_ACCOUNT -> FormColumn("wizard_onedrive_account") {
                Text(stringResource(R.string.wizard_onedrive_signed_in), Modifier.testTag("wizard_signed_in"))
                ActionButton(stringResource(R.string.wizard_onedrive_use_current), !oneDrive.busy && !busy,
                    Modifier.testTag("wizard_use_current"), ButtonKind.PRIMARY) { model.useCurrentAccount() }
                ActionButton(stringResource(R.string.wizard_onedrive_other_account), !oneDrive.busy && !busy,
                    Modifier.testTag("wizard_other_account")) {
                    model.loginRequested()
                    actions.login(true)
                }
                Text(stringResource(R.string.wizard_onedrive_other_note))
            }
            AdditionStep.ONEDRIVE_DIRECTORY -> DirectoryPage(directories, oneDrive, model::refresh)
            AdditionStep.CONFIRM -> {
                val chosen = requireNotNull(addition?.location)
                FormColumn("wizard_confirm") {
                    Text(stringResource(R.string.wizard_confirm_prompt))
                    Text(stringResource(R.string.wizard_confirm_type, stringResource(backendName(chosen.backend))), Modifier.testTag("wizard_confirm_type"))
                    Text(stringResource(R.string.wizard_confirm_directory, addition.displayName ?: stringResource(R.string.libraries_unnamed)),
                        Modifier.testTag("wizard_confirm_directory"), fontWeight = FontWeight.Bold)
                    if (model.entries.orEmpty().any { it.library.location == chosen }) {
                        Text(stringResource(R.string.wizard_confirm_existing), Modifier.testTag("wizard_confirm_existing"))
                    }
                    model.issue?.let { Text(stringResource(issueText(it)), Modifier.testTag("wizard_issue")) }
                }
            }
        }
    }
}

/** Step 1: the prompt as plain text and each type as a thick-bordered card with an icon, so neither reads as a button row. */
@Composable
private fun TypeStep(model: LibraryListViewModel, busy: Boolean, choose: (BackendKind) -> Unit) = FormColumn("wizard_type") {
    Heading(stringResource(R.string.wizard_type_prompt))
    BackendCard("wizard_type_local", R.drawable.ic_folder, stringResource(R.string.more_location_local),
        stringResource(R.string.wizard_type_local_description), !busy) { choose(BackendKind.LOCAL) }
    val configured = OneDriveOAuthConfiguration.fromBuildConfiguration() != null
    BackendCard("wizard_type_onedrive", R.drawable.ic_cloud, stringResource(R.string.more_location_onedrive),
        stringResource(if (configured) R.string.wizard_type_onedrive_description else R.string.wizard_type_onedrive_unavailable),
        configured && !busy) { choose(BackendKind.ONEDRIVE) }
    model.issue?.let { Text(stringResource(issueText(it))) }
}

/** An option block (Q58, Q68): framed thickly with small corners, an icon, the name over its description. */
@Composable
private fun BackendCard(tag: String, @DrawableRes icon: Int, name: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) INK else DISABLED_TINT
    Row(
        Modifier.fillMaxWidth().border(if (enabled) FRAME else BORDER, tint, SMALL_SHAPE)
            .tap(enabled, onClick = onClick)
            .semantics { contentDescription = "$name，$description" }
            .padding(PAGE_MARGIN).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(ICON_SIZE), tint = tint)
        Spacer(Modifier.width(LEADING_GAP))
        Column(Modifier.weight(1f)) {
            Text(name, color = tint, style = MaterialTheme.typography.titleSmall)
            Text(description, color = tint, style = MaterialTheme.typography.bodySmall)
        }
        Icon(painterResource(R.drawable.ic_next_page), null, Modifier.size(ICON_SIZE), tint = tint)
    }
}

/** A login in progress or needing redoing, and its last problem; being signed in needs no line. */
@Composable
private fun LoginState(oneDrive: OneDriveAuthorizationViewModel) {
    when (oneDrive.status) {
        LoginStatus.BROWSER -> R.string.onedrive_browser
        LoginStatus.EXCHANGING -> R.string.onedrive_exchanging
        LoginStatus.RELOGIN -> R.string.onedrive_relogin
        else -> null
    }?.let { Text(stringResource(it), Modifier.testTag("onedrive_status")) }
    oneDrive.issue?.let {
        Text(stringResource(when (it) {
            LoginIssue.CANCELED -> R.string.onedrive_canceled
            LoginIssue.NETWORK -> R.string.onedrive_network
            LoginIssue.NO_BROWSER -> R.string.onedrive_no_browser
            LoginIssue.SERVER -> R.string.onedrive_server
            LoginIssue.CALLBACK -> R.string.onedrive_callback
            LoginIssue.STORAGE -> R.string.onedrive_storage
            LoginIssue.RELOGIN -> R.string.onedrive_relogin
            LoginIssue.EXPIRED -> R.string.onedrive_expired
        }), Modifier.testTag("onedrive_issue"))
    }
}
