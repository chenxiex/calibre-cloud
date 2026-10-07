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

/** Values within one dimension are alternatives (or); non-empty dimensions combine with and. */
data class LibraryFilters(
    val downloads: Set<DownloadFilter> = emptySet(),
    val reads: Set<ReadFilter> = emptySet(),
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
        val Default = BookSort(BookSortKey.ADDED, ascending = false)

        /** Inside a series folder the series index ascends by default. */
        fun defaultFor(categorization: Categorization, folder: FolderKey?): BookSort =
            if (categorization == Categorization.Series && folder?.name != null) BookSort(BookSortKey.SERIES_INDEX, true) else Default
    }
}

/** Exact category value; a null [name] is the folder of books without any value. */
data class FolderKey(val name: String?)

/**
 * One immutable, stable-ordered page request. A non-blank [search] always covers the whole library
 * and flattens to books, ignoring [categorization] and [folder]; filters still apply. Otherwise a
 * categorization without a [folder] lists folders, and with a [folder] its books.
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
