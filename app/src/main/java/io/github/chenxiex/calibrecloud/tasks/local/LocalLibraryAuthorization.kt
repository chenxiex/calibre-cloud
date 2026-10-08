package io.github.chenxiex.calibrecloud.tasks.local

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.tasks.api.CandidateContext
import io.github.chenxiex.calibrecloud.tasks.api.LibraryAuthorization

/**
 * A local library is authorized by the persisted directory grant of its selection; the selection token
 * stands in as the non-secret authorization identifier. Re-authorizing means choosing the directory
 * again, which makes a new selection, so a bound context never goes stale on its own; a revoked grant
 * surfaces from the source operations instead.
 */
class LocalLibraryAuthorization(private val granted: suspend () -> Boolean) : LibraryAuthorization {
    override val backend = BackendKind.LOCAL

    override suspend fun ready() = granted()

    override suspend fun bind(selection: LibrarySelection) =
        CandidateContext(selection.token, backend, selection.authorizationId ?: selection.token)

    override suspend fun check(context: CandidateContext) = Unit

    override suspend fun <T> within(context: CandidateContext, block: suspend () -> T): T = block()
}
