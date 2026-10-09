package io.github.chenxiex.calibrecloud.tasks.local

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.storage.local.DirectoryAuthorizationStatus
import io.github.chenxiex.calibrecloud.tasks.api.CandidateContext
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAccess
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorization

/**
 * A local library is authorized by the persisted directory grant it was listed with (its access key);
 * the selection token stands in as the non-secret authorization identifier. Re-authorizing replaces
 * the grant of the same location, so a bound context never goes stale on its own; a revoked grant
 * surfaces from the source operations instead.
 */
class LocalLibraryAuthorization(
    /** The grant state of an access key. */
    private val status: suspend (String?) -> DirectoryAuthorizationStatus,
    /** The access key of the current library, if it is a listed local library. */
    private val currentKey: suspend () -> String?,
) : LibraryAuthorization {
    override val backend = BackendKind.LOCAL

    override suspend fun ready() = usable(status(currentKey()))

    override suspend fun access(location: LibraryLocation, accessKey: String?) = when (status(accessKey)) {
        DirectoryAuthorizationStatus.AUTHORIZED -> LibraryAccess.READY
        DirectoryAuthorizationStatus.READ_ONLY -> LibraryAccess.READ_ONLY
        else -> LibraryAccess.REAUTHORIZE
    }

    override suspend fun bind(selection: LibrarySelection) =
        CandidateContext(selection.token, backend, selection.authorizationId ?: selection.token)

    override suspend fun check(context: CandidateContext) = Unit

    override suspend fun <T> within(context: CandidateContext, block: suspend () -> T): T = block()

    private fun usable(status: DirectoryAuthorizationStatus) =
        status == DirectoryAuthorizationStatus.AUTHORIZED || status == DirectoryAuthorizationStatus.READ_ONLY
}
