package io.github.chenxiex.calibrecloud.storage.api

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryLocation

/**
 * The persisted form of a [LibraryLocation]: application state keeps the backend code and this key,
 * compares keys only for equality and never reads their parts, so a new backend needs no schema change.
 * Equal locations always produce the same key. Parts are joined by a control character, which
 * location parts never contain.
 */
object LocationKeys {
    private const val SEPARATOR = '\u001f'

    fun backendCode(backend: BackendKind): String = backend.name.lowercase()

    fun backend(code: String): BackendKind = BackendKind.entries.single { backendCode(it) == code }

    fun encode(location: LibraryLocation): String = when (location) {
        is LibraryLocation.Local -> listOf(location.authority, location.treeDocumentId)
        is LibraryLocation.OneDrive -> listOf(location.accountId, location.driveId, location.rootItemId)
    }.joinToString(SEPARATOR.toString())

    fun decode(backend: BackendKind, key: String): LibraryLocation {
        val parts = key.split(SEPARATOR)
        return when (backend) {
            BackendKind.LOCAL -> {
                require(parts.size == 2)
                LibraryLocation.Local(parts[0], parts[1])
            }
            BackendKind.ONEDRIVE -> {
                require(parts.size == 3)
                LibraryLocation.OneDrive(parts[0], parts[1], parts[2])
            }
        }
    }
}
