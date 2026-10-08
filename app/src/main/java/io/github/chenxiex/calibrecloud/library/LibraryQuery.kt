package io.github.chenxiex.calibrecloud.library

import io.github.chenxiex.calibrecloud.metadata.LibraryRevision
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId

/** Field restriction of a search; [All] covers every supported text field of the book. */
sealed interface SearchScope {
    data object All : SearchScope
    data object Title : SearchScope
    data object Authors : SearchScope
    data object Series : SearchScope
    data object Tags : SearchScope
    data object Comments : SearchScope
    /** A text, enumeration or multi-value text custom column. */
    data class Column(val id: CustomColumnId) : SearchScope
}

enum class DownloadFilter { DOWNLOADED, NOT_DOWNLOADED }
enum class ReadFilter { READ, UNREAD }

/**
 * Download and read state take at most one value each; several formats are alternatives (or). Set
 * dimensions combine with and.
 */
data class LibraryFilters(
    val download: DownloadFilter? = null,
    val read: ReadFilter? = null,
    val formats: Set<BookFormat> = emptySet(),
)

sealed interface Categorization {
    data object None : Categorization
    data object Series : Categorization
    data object Tags : Categorization
    /** Only a text, enumeration or multi-value text column can classify. */
    data class Column(val id: CustomColumnId) : Categorization
}

enum class BookSortKey { TITLE, ADDED, RATING, SERIES_INDEX }

/** A missing rating or series index sorts after present values in either direction. */
data class BookSort(val key: BookSortKey, val ascending: Boolean) {
    companion object {
        val Default = of(BookSortKey.ADDED)

        /** The direction a key starts with when chosen: title and series index ascend, time and rating descend. */
        fun of(key: BookSortKey): BookSort = BookSort(key, key == BookSortKey.TITLE || key == BookSortKey.SERIES_INDEX)

        /** Inside a series folder the series index ascends by default. */
        fun defaultFor(categorization: Categorization, folder: FolderKey?): BookSort =
            if (categorization == Categorization.Series && folder?.name != null) of(BookSortKey.SERIES_INDEX) else Default
    }
}

/** Exact category value; a null [name] is the folder of books without any value. */
data class FolderKey(val name: String?)

/**
 * One immutable, stable-ordered page request. A non-blank [search] always covers the whole library
 * and flattens to books, ignoring [categorization] and [folder]; filters still apply. Otherwise a
 * categorization without a [folder] lists folders, and with a [folder] its books. Folders follow their
 * name in the [foldersAscending] direction; the no-value folder stays last either way.
 * [formatPriority] lists the preferred formats first; others follow by name. When [expected] is set
 * and no longer current, the result is [LibraryQueryResult.Stale].
 */
data class LibraryRequest(
    val search: String = "",
    val searchScope: SearchScope = SearchScope.All,
    val filters: LibraryFilters = LibraryFilters(),
    val categorization: Categorization = Categorization.None,
    val folder: FolderKey? = null,
    val sort: BookSort = BookSort.Default,
    val foldersAscending: Boolean = true,
    val formatPriority: List<BookFormat> = DEFAULT_FORMAT_PRIORITY,
    val offset: Int = 0,
    val pageSize: Int,
    val expected: LibraryRevision? = null,
) {
    init {
        require(offset >= 0 && pageSize > 0)
    }

    companion object {
        val DEFAULT_FORMAT_PRIORITY = listOf(BookFormat.parse("EPUB"))
    }
}

data class CategoryColumn(val id: CustomColumnId, val name: String)

/**
 * What a page needs to describe the current import without reading any rows. [categoryColumns] also
 * limit a search; [formats] are the source formats present in the import, by name; the read filter is
 * offered only when [readFilterAvailable].
 */
data class LibraryOverview(
    val revision: LibraryRevision,
    val importedAt: Long,
    val categoryColumns: List<CategoryColumn>,
    val readFilterAvailable: Boolean,
    val formats: List<BookFormat>,
)

/** Stable reasons; pages resolve them to resources. Not a source of translated text. */
enum class LibraryProblem {
    NO_METADATA,
    READ_FILTER_UNAVAILABLE,
    CATEGORY_COLUMN_INVALID,
    SEARCH_COLUMN_INVALID,
}

enum class NoFormatReason { NO_FORMAT }

/**
 * Format chosen by R24: the highest-priority cached format, else the highest-priority source format.
 * [sizeBytes] is null when unknown. [sourceMissing] marks a complete copy that is not (or no longer)
 * backed by the source library.
 */
data class DefaultFormat(
    val format: BookFormat,
    val cached: Boolean,
    val sizeBytes: Long?,
    val sourceMissing: Boolean,
)

data class BookRow(
    val key: BookKey,
    val title: String,
    /** Empty when the source has no author; nothing is substituted. */
    val authors: List<String>,
    val hasCover: Boolean,
    /** Null when no valid read column is configured. */
    val read: Boolean?,
    /** At least one complete copy within the current format filter scope. */
    val downloaded: Boolean,
    val defaultFormat: DefaultFormat?,
    val noFormat: NoFormatReason?,
)

data class FolderRow(val key: FolderKey, val bookCount: Int, val representative: BookRow)

sealed interface LibraryQueryResult {
    /** [total] counts all matches; [rows] is the stable slice starting at [offset]. */
    data class Books(val revision: LibraryRevision, val total: Int, val offset: Int, val rows: List<BookRow>) : LibraryQueryResult
    data class Folders(val revision: LibraryRevision, val total: Int, val offset: Int, val rows: List<FolderRow>) : LibraryQueryResult
    data class Unavailable(val problem: LibraryProblem) : LibraryQueryResult
    /** The library, import generation or read column changed since the request's expectation. */
    data object Stale : LibraryQueryResult
}

/**
 * One book of an expanded selection. [download] is the format a batch download takes: the
 * highest-priority source format within the format filter, null when the book has none; [downloaded]
 * says a complete copy of that format already exists. [read] is null without a valid read column.
 */
data class SelectedBook(val key: BookKey, val read: Boolean?, val download: BookFormat?, val downloaded: Boolean)

/** The deduplicated books of a selection, in the level's book order. */
data class SelectionExpansion(val revision: LibraryRevision, val readColumnValid: Boolean, val books: List<SelectedBook>)

sealed interface SelectionResult {
    data class Expanded(val expansion: SelectionExpansion) : SelectionResult
    data class Unavailable(val problem: LibraryProblem) : SelectionResult
    data object Stale : SelectionResult
}

enum class ReadMarkAction { MARK_READ, MARK_UNREAD }

/** Why the read-state mark cannot be submitted, in the order they are reported. */
enum class ReadMarkBlock { NO_BOOKS, COLUMN_UNAVAILABLE, WRITE_UNAVAILABLE }

/** The one read-state operation offered for a selection, and why it is disabled when [blocked] is set. */
data class ReadMarkChoice(val action: ReadMarkAction, val blocked: ReadMarkBlock?) {
    companion object {
        /**
         * R26 on the last successful import: an all-read set offers "mark unread"; all unread (an empty
         * value counts as unread) or mixed offers "mark read". Queued writes do not change the choice.
         */
        fun of(expansion: SelectionExpansion, writeAvailable: Boolean): ReadMarkChoice {
            val books = expansion.books
            val action = if (books.isNotEmpty() && books.all { it.read == true }) ReadMarkAction.MARK_UNREAD else ReadMarkAction.MARK_READ
            val blocked = when {
                books.isEmpty() -> ReadMarkBlock.NO_BOOKS
                !expansion.readColumnValid -> ReadMarkBlock.COLUMN_UNAVAILABLE
                !writeAvailable -> ReadMarkBlock.WRITE_UNAVAILABLE
                else -> null
            }
            return ReadMarkChoice(action, blocked)
        }
    }
}
