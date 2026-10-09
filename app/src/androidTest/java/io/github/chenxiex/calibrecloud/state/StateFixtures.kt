package io.github.chenxiex.calibrecloud.state

import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.tasks.api.CandidateContext
import java.util.UUID

/** Lists [location] with [accessKey] and makes it current, as completing the add-library wizard does. */
suspend fun ApplicationStateRepository.addLibrary(location: LibraryLocation, accessKey: String? = null, name: String? = null): LibrarySelection {
    val addition = beginAddition(location.backend, null).first
    chooseAddition(addition.token, location, name, accessKey).getOrThrow()
    return requireNotNull(completeAddition(addition.token)).first
}

/** Makes [location] current under the sign-in [session], as a sync of a newly selected OneDrive library binds it. */
suspend fun ApplicationStateRepository.selectSignedIn(location: LibraryLocation, session: UUID): CandidateContext =
    requireNotNull(reauthorizeCandidate(select(location).token, session))
