package io.github.chenxiex.calibrecloud.tasks.onedrive

import io.github.chenxiex.calibrecloud.auth.LoginIssue
import io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationSession
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.storage.api.SourceFailure
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.CandidateContext
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAccess
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorization
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * OneDrive requests are bound to a login session. After a new login the selected position is rebound to
 * the new session, which revokes requests of the old one. A refresh that failed for network or server
 * reasons is not a lost login: it surfaces as a transient network failure. Only one login is kept (Q60):
 * a library of another account needs signing in again.
 */
class OneDriveLibraryAuthorization(
    private val state: ApplicationStateRepository,
    private val sessionId: suspend () -> UUID?,
    private val issue: () -> LoginIssue?,
    /** The signed-in account in the form locations store; null when signed out or not recorded. */
    private val account: suspend () -> String? = { null },
) : LibraryAuthorization {
    override val backend = BackendKind.ONEDRIVE

    override suspend fun ready() = sessionId() != null

    /** A login without a recorded account is accepted here; source access still verifies the account. */
    override suspend fun access(location: LibraryLocation, accessKey: String?): LibraryAccess {
        if (sessionId() == null) return LibraryAccess.REAUTHORIZE
        val signedIn = account() ?: return LibraryAccess.READY
        return if (signedIn == (location as? LibraryLocation.OneDrive)?.accountId) LibraryAccess.READY else LibraryAccess.REAUTHORIZE
    }

    override suspend fun bind(selection: LibrarySelection): CandidateContext? {
        val session = sessionId() ?: selection.authorizationId ?: return null
        return if (selection.authorizationId == session) CandidateContext(selection.token, backend, session)
            else state.reauthorizeCandidate(selection.token, session)
    }

    override suspend fun check(context: CandidateContext) {
        if (sessionId() != context.authorizationId) throw SourceFailure(StorageErrorKind.LOGIN_REQUIRED)
    }

    override suspend fun <T> within(context: CandidateContext, block: suspend () -> T): T =
        withContext(OneDriveAuthorizationSession(context.authorizationId)) {
            try {
                block()
            } catch (failure: SourceFailure) {
                if (failure.error.kind != StorageErrorKind.LOGIN_REQUIRED || sessionId() != context.authorizationId ||
                    issue() !in setOf(LoginIssue.NETWORK, LoginIssue.SERVER)) throw failure
                throw SourceFailure(StorageError(StorageErrorKind.NO_NETWORK), transient = true, failure.retryDelayMillis)
            }
        }
}
