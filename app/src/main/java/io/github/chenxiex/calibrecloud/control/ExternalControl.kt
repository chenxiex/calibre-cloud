package io.github.chenxiex.calibrecloud.control

import io.github.chenxiex.calibrecloud.ApplicationDependencies
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.state.ExternalControlStore
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import java.util.UUID

/** Why a request was not carried out; each has its notification text (Q88). */
enum class ExternalRejection {
    /** The switch is off. */
    DISABLED,
    /** The token is not registered, or was removed. */
    UNREGISTERED,
    /** The operation is not one this version knows. */
    UNKNOWN_OPERATION,
    /** A parameter is missing or malformed. */
    MALFORMED,
    /** No library is current, or it has no import. */
    NO_LIBRARY,
    /** No copy was handed to a reader under this name in any library. */
    NOT_OPENED,
    /** Only another library handed a copy out under this name. */
    OTHER_LIBRARY,
    /** The book is no longer in the current import. */
    NOT_IN_LIBRARY,
    /** No valid read column is chosen. */
    NO_READ_COLUMN,
    /** The source cannot be written. */
    NOT_WRITABLE,
}

sealed interface ExternalOutcome {
    data object Accepted : ExternalOutcome
    data class Rejected(val reason: ExternalRejection) : ExternalOutcome
}

/** What the control logic needs from the app; replaceable in tests. */
interface ExternalControlPort {
    val store: ExternalControlStore

    /** The current library and the selection token that guards a submission; null without one. */
    suspend fun current(): Pair<LibraryId, UUID>?

    /** Copies handed to a reader as [readerName], in any library. */
    suspend fun opened(readerName: String): List<CopyKey>

    /** The page's "mark as read / unread" submission (R14). */
    suspend fun submit(book: BookKey, target: Boolean, selectionToken: UUID): SubmissionResult

    suspend fun wake()
}

/**
 * Carries out an external reader's request (R37) exactly as the page's button would: the book found
 * through the copies this app opened in the current library is submitted to the read status write,
 * which merges, writes back and syncs as R13–R16 describe. Nothing here reads or writes the source.
 */
class ExternalControl(private val port: ExternalControlPort) {
    suspend fun markReadStatus(request: ExternalRequest.MarkReadStatus): ExternalOutcome {
        if (!port.store.enabled()) return rejected(ExternalRejection.DISABLED)
        if (!port.store.registered(ExternalProtocol.tokenHash(request.token))) return rejected(ExternalRejection.UNREGISTERED)
        val (library, selection) = port.current() ?: return rejected(ExternalRejection.NO_LIBRARY)
        val opened = port.opened(request.file)
        val book = opened.singleOrNull { it.book.libraryId == library }?.book
            ?: return rejected(if (opened.isEmpty()) ExternalRejection.NOT_OPENED else ExternalRejection.OTHER_LIBRARY)
        return when (val result = port.submit(book, request.target, selection)) {
            is SubmissionResult.Created, is SubmissionResult.Reused, is SubmissionResult.Promoted -> {
                port.wake()
                ExternalOutcome.Accepted
            }
            is SubmissionResult.Rejected -> rejected(when (val error = result.error) {
                TaskError.InvalidColumn -> ExternalRejection.NO_READ_COLUMN
                is TaskError.Source -> if (error.error.kind == StorageErrorKind.UNSUPPORTED_OPERATION) ExternalRejection.NOT_WRITABLE
                    else ExternalRejection.NOT_IN_LIBRARY
                is TaskError.BookIdentityChanged -> ExternalRejection.NOT_IN_LIBRARY
            })
        }
    }

    /** Registers after the user confirmed; turns the switch on too when it was off (Q92). */
    suspend fun register(request: ExternalRequest.Register) {
        port.store.register(request.name, ExternalProtocol.tokenHash(request.token), enable = true)
    }

    private fun rejected(reason: ExternalRejection) = ExternalOutcome.Rejected(reason)
}

/** Production port: the shared state, the opened-copy records and the read status service. */
class AppExternalControlPort(private val dependencies: ApplicationDependencies) : ExternalControlPort {
    override val store: ExternalControlStore get() = dependencies.externalControl

    override suspend fun current(): Pair<LibraryId, UUID>? {
        val selection = dependencies.state.current() ?: return null
        return selection.identity?.let { it.id to selection.token }
    }

    override suspend fun opened(readerName: String) = dependencies.lastOpened.opened(readerName)

    override suspend fun submit(book: BookKey, target: Boolean, selectionToken: UUID) =
        dependencies.readStatusService.submit(listOf(book), target, selectionToken)

    override suspend fun wake() = dependencies.taskCoordinator.requestRun()
}
