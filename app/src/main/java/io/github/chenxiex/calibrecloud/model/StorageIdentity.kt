package io.github.chenxiex.calibrecloud.model

import java.util.Locale
import java.util.UUID

private fun requireOpaque(value: String) {
    require(value.isNotBlank() && value.none { Character.isISOControl(it) })
}

/** Allocated per validated library incarnation; never derived from a display name or book ID. */
data class LibraryId(val value: UUID)

enum class BackendKind { LOCAL, ONEDRIVE }

sealed interface LibraryLocation {
    val backend: BackendKind

    data class Local(val authority: String, val treeDocumentId: String) : LibraryLocation {
        override val backend = BackendKind.LOCAL
        init {
            requireOpaque(authority)
            requireOpaque(treeDocumentId)
        }
    }

    /** accountId is the stable account subject, not an email address or display name. */
    data class OneDrive(val accountId: String, val driveId: String, val rootItemId: String) : LibraryLocation {
        override val backend = BackendKind.ONEDRIVE
        init {
            requireOpaque(accountId)
            requireOpaque(driveId)
            requireOpaque(rootItemId)
        }
    }
}

/** Binding and replacement detection belong to the validated backend, not to the UI. */
data class LibraryIdentity(val id: LibraryId, val location: LibraryLocation, val generation: UUID)

data class BookKey(val libraryId: LibraryId, val sourceId: Long, val sourceUuid: UUID) {
    init { require(sourceId > 0) }
}

@ConsistentCopyVisibility
data class BookFormat private constructor(val value: String) {
    init { require(value.matches(Regex("[A-Z0-9]+"))) }
    companion object {
        fun parse(value: String): BookFormat {
            require(value.matches(Regex("[A-Za-z0-9]+")))
            return BookFormat(value.uppercase(Locale.ROOT))
        }
    }
}

/** Logical Calibre path only. Backends must additionally resolve it inside their authorized root. */
data class RelativeSourcePath(val value: String) {
    init {
        require(value.isNotEmpty() && !value.startsWith('/') && !value.contains('\\'))
        require(value.none { Character.isISOControl(it) } && !value.contains(':'))
        require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    }
}

/** Imported numeric ID plus lookup name detects a replaced custom column. No fixed read-status name. */
data class CustomColumnId(val sourceId: Long, val lookupName: String) {
    init {
        require(sourceId > 0)
        require(lookupName.matches(Regex("#[A-Za-z][A-Za-z0-9_]*")))
    }
}

sealed interface SourceFileLocator {
    val backend: BackendKind

    /** Frozen imported logical path; resolved inside the bound root by the executing backend. */
    data class Relative(override val backend: BackendKind, val path: RelativeSourcePath) : SourceFileLocator

    data class Local(val documentId: String) : SourceFileLocator {
        override val backend = BackendKind.LOCAL
        init { requireOpaque(documentId) }
    }

    data class OneDrive(val driveId: String, val itemId: String) : SourceFileLocator {
        override val backend = BackendKind.ONEDRIVE
        init {
            requireOpaque(driveId)
            requireOpaque(itemId)
        }
    }
}

data class FormatResource(val book: BookKey, val format: BookFormat, val source: SourceFileLocator)

data class CopyKey(val book: BookKey, val format: BookFormat)

/** Backend-produced file token, never a book metadata modification timestamp. Do not log its value. */
data class FileVersion(val backend: BackendKind, val token: String) {
    init { requireOpaque(token) }
    override fun toString() = "FileVersion(backend=$backend)"
}
