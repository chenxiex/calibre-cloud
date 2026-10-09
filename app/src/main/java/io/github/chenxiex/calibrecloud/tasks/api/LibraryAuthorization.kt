package io.github.chenxiex.calibrecloud.tasks.api

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.LibrarySelection

/** Whether a listed library can be used now (R30): fully, without source writes, or after re-authorizing. */
enum class LibraryAccess { READY, READ_ONLY, REAUTHORIZE }

/**
 * A backend's authorization as library syncs see it. Every backend implements the whole contract and
 * callers select one by backend through [LibraryAuthorizations]; how a backend authorizes (a sign-in
 * session, a persisted directory grant) stays inside the implementation. A sync request is bound to the
 * selection token and the authorization identifier in its [CandidateContext].
 */
interface LibraryAuthorization {
    val backend: BackendKind

    /** Whether work that waits for this backend's re-authorization can continue now. */
    suspend fun ready(): Boolean

    /** Whether [location], listed with [accessKey], can be used now; decided locally, with no source request. */
    suspend fun access(location: LibraryLocation, accessKey: String?): LibraryAccess

    /**
     * The context a new sync of the current [selection] runs under. When the authorization changed since
     * the selection was made, the selection is rebound to it under a new token. null when none can be bound.
     */
    suspend fun bind(selection: LibrarySelection): CandidateContext?

    /** Throws a classified source failure when [context]'s authorization is no longer the current one. */
    suspend fun check(context: CandidateContext)

    /** Runs [block], which accesses the source, under [context]'s authorization. */
    suspend fun <T> within(context: CandidateContext, block: suspend () -> T): T
}

fun interface LibraryAuthorizations {
    fun of(backend: BackendKind): LibraryAuthorization
}
