package io.github.chenxiex.calibrecloud.ui

import io.github.chenxiex.calibrecloud.library.BookSort
import io.github.chenxiex.calibrecloud.library.BookSortKey
import io.github.chenxiex.calibrecloud.library.Categorization
import io.github.chenxiex.calibrecloud.library.DownloadFilter
import io.github.chenxiex.calibrecloud.library.LibraryFilters
import io.github.chenxiex.calibrecloud.library.ReadFilter
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository

/**
 * The library page settings restored on the next launch (R21, Q82): view mode, categorization, the
 * root's book sort, the folder name direction and the filters. The open folder, its sort, the page,
 * a selection and the search session are not kept. Global, like in a session: a column that is not
 * valid in the shown library is reported by the query, never rewritten here.
 */
data class SavedLibraryView(
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val categorization: Categorization = Categorization.None,
    val sort: BookSort = BookSort.Default,
    val foldersAscending: Boolean = true,
    val filters: LibraryFilters = LibraryFilters(),
) {
    /** One `key=value` line per setting; absent filter dimensions are left out. */
    fun encode(): String = buildList {
        add("view=${viewMode.name}")
        add("category=" + when (val value = categorization) {
            Categorization.None -> "none"
            Categorization.Series -> "series"
            Categorization.Tags -> "tags"
            is Categorization.Column -> "column:${value.id.sourceId}:${value.id.lookupName}"
        })
        add("sort=${sort.key.name}:${direction(sort.ascending)}")
        add("folders=${direction(foldersAscending)}")
        filters.download?.let { add("download=${it.name}") }
        filters.read?.let { add("read=${it.name}") }
        if (filters.formats.isNotEmpty()) add("formats=" + filters.formats.map { it.value }.sorted().joinToString(","))
    }.joinToString("\n")

    companion object {
        /** Reads [encode]'s text; a line that no longer parses keeps that setting's default. */
        fun decode(text: String): SavedLibraryView {
            val values = text.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val default = SavedLibraryView()
            return SavedLibraryView(
                viewMode = values["view"]?.let { parse { LibraryViewMode.valueOf(it) } } ?: default.viewMode,
                categorization = values["category"]?.let { parse { categorization(it) } } ?: default.categorization,
                sort = values["sort"]?.let { parse { sort(it) } } ?: default.sort,
                foldersAscending = values["folders"]?.let { ascending(it) } ?: default.foldersAscending,
                filters = LibraryFilters(
                    download = values["download"]?.let { parse { DownloadFilter.valueOf(it) } },
                    read = values["read"]?.let { parse { ReadFilter.valueOf(it) } },
                    formats = values["formats"]?.split(',')?.mapNotNull { parse { BookFormat.parse(it) } }?.toSet().orEmpty(),
                ),
            )
        }

        private fun direction(ascending: Boolean) = if (ascending) "asc" else "desc"

        private fun ascending(text: String) = when (text) {
            "asc" -> true
            "desc" -> false
            else -> null
        }

        private fun categorization(text: String): Categorization? = when {
            text == "none" -> Categorization.None
            text == "series" -> Categorization.Series
            text == "tags" -> Categorization.Tags
            text.startsWith("column:") -> text.split(':', limit = 3).let { Categorization.Column(CustomColumnId(it[1].toLong(), it[2])) }
            else -> null
        }

        private fun sort(text: String): BookSort? {
            val (key, direction) = text.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return BookSort(BookSortKey.valueOf(key), ascending(direction) ?: return null)
        }

        private fun <T> parse(read: () -> T?): T? = try {
            read()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}

/** Where [SavedLibraryView] is kept; replaceable in tests. */
interface LibraryViewStore {
    suspend fun load(): SavedLibraryView?
    suspend fun save(view: SavedLibraryView)

    /** Keeps nothing: every launch starts with the defaults. */
    object None : LibraryViewStore {
        override suspend fun load(): SavedLibraryView? = null
        override suspend fun save(view: SavedLibraryView) {}
    }
}

/** The view kept in the application settings, which no cache cleanup removes. */
class SettingsLibraryViewStore(private val state: ApplicationStateRepository) : LibraryViewStore {
    override suspend fun load() = state.libraryView()?.let(SavedLibraryView::decode)
    override suspend fun save(view: SavedLibraryView) = state.setLibraryView(view.encode())
}
