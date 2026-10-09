package io.github.chenxiex.calibrecloud.tasks

import io.github.chenxiex.calibrecloud.auth.LoginIssue
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorizations
import io.github.chenxiex.calibrecloud.tasks.local.LocalLibraryAuthorization
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveLibraryAuthorization
import java.util.UUID

/** The production authorization of every backend, with the login session and directory grant injected. */
object TestAuthorizations {
    fun of(
        state: ApplicationStateRepository,
        sessionId: suspend () -> UUID? = { null },
        issue: () -> LoginIssue? = { null },
        localGranted: suspend () -> Boolean = { true },
    ): LibraryAuthorizations {
        val local = LocalLibraryAuthorization({
            if (localGranted()) DirectoryAuthorizationStatus.AUTHORIZED else DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED
        }) { null }
        val oneDrive = OneDriveLibraryAuthorization(state, sessionId, issue)
        return LibraryAuthorizations { backend ->
            when (backend) {
                BackendKind.LOCAL -> local
                BackendKind.ONEDRIVE -> oneDrive
            }
        }
    }
}
