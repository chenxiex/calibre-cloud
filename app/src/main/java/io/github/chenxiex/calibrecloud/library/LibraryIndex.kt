package io.github.chenxiex.calibrecloud.library

import io.github.chenxiex.calibrecloud.metadata.ImportedBook
import io.github.chenxiex.calibrecloud.metadata.ImportedColumnValue
import io.github.chenxiex.calibrecloud.metadata.ImportedLibrary
import io.github.chenxiex.calibrecloud.metadata.ReadColumnStatus
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import java.text.Collator
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

private val COLUMN_TYPES = setOf("text", "enumeration")

/**
 * Immutable in-memory index of one import generation and read-column configuration. Built from the
 * private imported payload only; queries perform no source, backend or task access. Complete copies
 * are supplied per query because they change independently of the import.
 *
 * Search is literal: keywords split on whitespace, each must be contained (case-insensitively) in a
 * field of the scope, and all must match. No query language or SQL is interpreted.
 */
class LibraryIndex(private val library: ImportedLibrary) {
    private class Entry(
        val book: ImportedBook,
        val key: BookKey,
        val addedEpoch: Long,
        val title: List<String>,
        val authors: List<String>,
        val series: List<String>,
        val tags: List<String>,
        val comments: List<String>,
        val columns: Map<Long, List<String>>,
        val read: Boolean?,
    )

    private val libraryId: LibraryId = library.identity.id
    private val entries: List<Entry> = library.metadata.books.map { book ->
        Entry(book, BookKey(libraryId, book.sourceId, book.sourceUuid), epoch(book.addedAt),
            listOf(lower(book.title)), book.authors.map(::lower), listOfNotNull(book.series).map(::lower),
            book.tags.map(::lower), listOf(lower(book.commentsText)),
            book.customValues.mapNotNull { (id, value) ->
                (value as? ImportedColumnValue.Text)?.let { id to it.values.map(::lower) }
            }.toMap(), library.isRead(book))
    }
    private val searchableColumns: Set<CustomColumnId> = library.metadata.columns
        .filter { it.supported && it.datatype in COLUMN_TYPES }.map { it.id }.toSet()

    val revision get() = library.revision

    /** Import time of the generation this index serves, for the last-sync display. */
    val importedAt get() = library.importedAt

    /** Columns that can classify or limit a search, in the library's column order. */
    val categoryColumns: List<CategoryColumn> = library.metadata.columns
        .filter { it.supported && it.datatype in COLUMN_TYPES }.map { CategoryColumn(it.id, it.name) }

    fun query(request: LibraryRequest, copies: List<DownloadedCopy>): LibraryQueryResult {
        request.expected?.let { if (it != revision) return LibraryQueryResult.Stale }
        val cached: Map<BookKey, Map<BookFormat, DownloadedCopy>> = copies
            .filter { it.key.book.libraryId == libraryId }
            .groupBy({ it.key.book }, { it.key.format to it }).mapValues { it.value.toMap() }
        val scope = request.searchScope
        if (scope is SearchScope.Column && scope.id !in searchableColumns) return problem(LibraryProblem.SEARCH_COLUMN_INVALID)
        val categorization = request.categorization
        if (categorization is Categorization.Column && categorization.id !in searchableColumns) return problem(LibraryProblem.CATEGORY_COLUMN_INVALID)
        val filters = request.filters
        if (filters.reads.isNotEmpty() && library.readColumnStatus != ReadColumnStatus.VALID) {
            return problem(LibraryProblem.READ_FILTER_UNAVAILABLE)
        }

        val keywords = keywords(request.search)
        val matched = entries.filter { entry ->
            matchesSearch(entry, keywords, scope) && matchesFilters(entry, filters, resolve(entry, cached[entry.key], request))
        }
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.TERTIARY }
        val row = { entry: Entry -> toRow(entry, resolve(entry, cached[entry.key], request)) }

        if (keywords.isNotEmpty() || categorization == Categorization.None) {
            return books(request, matched, collator, row)
        }
        val groups = LinkedHashMap<FolderKey, MutableList<Entry>>()
        matched.forEach { entry ->
            val values = categoryValues(entry, categorization)
            if (values.isEmpty()) groups.getOrPut(FolderKey(null)) { mutableListOf() }.add(entry)
            else values.forEach { groups.getOrPut(FolderKey(it)) { mutableListOf() }.add(entry) }
        }
        val folder = request.folder
        if (folder != null) return books(request, groups[folder].orEmpty(), collator, row)

        // Folder level: name order only, the no-value folder last in both directions; representative is the newest book.
        val direction = if (request.foldersAscending) 1 else -1
        val ordered = groups.keys.sortedWith { a, b ->
            when {
                a.name == null -> if (b.name == null) 0 else 1
                b.name == null -> -1
                else -> direction * (collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.name.compareTo(b.name))
            }
        }
        val page = ordered.drop(request.offset).take(request.pageSize).map { key ->
            val members = groups.getValue(key)
            FolderRow(key, members.size, row(members.minWith(newestFirst(collator))))
        }
        return LibraryQueryResult.Folders(revision, ordered.size, request.offset, page)
    }

    private fun books(request: LibraryRequest, source: List<Entry>, collator: Collator, row: (Entry) -> BookRow): LibraryQueryResult {
        val sorted = source.sortedWith(bookOrder(request.sort, collator))
        return LibraryQueryResult.Books(revision, sorted.size, request.offset,
            sorted.drop(request.offset).take(request.pageSize).map(row))
    }

    private fun problem(reason: LibraryProblem) = LibraryQueryResult.Unavailable(reason)

    private fun matchesSearch(entry: Entry, keywords: List<String>, scope: SearchScope): Boolean {
        if (keywords.isEmpty()) return true
        val fields: Sequence<String> = when (scope) {
            SearchScope.All -> (entry.title + entry.authors + entry.series + entry.tags + entry.comments +
                entry.columns.values.flatten()).asSequence()
            SearchScope.Title -> entry.title.asSequence()
            SearchScope.Authors -> entry.authors.asSequence()
            SearchScope.Series -> entry.series.asSequence()
            SearchScope.Tags -> entry.tags.asSequence()
            SearchScope.Comments -> entry.comments.asSequence()
            is SearchScope.Column -> entry.columns[scope.id.sourceId].orEmpty().asSequence()
        }
        val values = fields.toList()
        return keywords.all { word -> values.any { it.contains(word) } }
    }

    private fun matchesFilters(entry: Entry, filters: LibraryFilters, resolved: Resolution): Boolean {
        if (filters.formats.isNotEmpty() && resolved.candidates.isEmpty()) return false
        if (filters.downloads.isNotEmpty() && (resolved.cached.isNotEmpty()) !in
            filters.downloads.map { it == DownloadFilter.DOWNLOADED }) return false
        if (filters.reads.isNotEmpty() && entry.read !in filters.reads.map { it == ReadFilter.READ }) return false
        return true
    }

    /** Formats considered for one book: source formats plus complete copies, limited by the format filter. */
    private class Resolution(
        val candidates: List<BookFormat>,
        val sourceFormats: Map<BookFormat, Long?>,
        val cached: Map<BookFormat, DownloadedCopy>,
    )

    private fun resolve(entry: Entry, copies: Map<BookFormat, DownloadedCopy>?, request: LibraryRequest): Resolution {
        val source = entry.book.formats.associate { it.format to it.sizeBytes }
        val scope = request.filters.formats
        val inScope = { format: BookFormat -> scope.isEmpty() || format in scope }
        val cached = copies.orEmpty().filterKeys(inScope)
        val order = priorityOrder(request.formatPriority)
        val candidates = (source.keys.filter(inScope) + cached.keys).distinct().sortedWith(order)
        return Resolution(candidates, source, cached)
    }

    private fun toRow(entry: Entry, resolved: Resolution): BookRow {
        // Cache first by priority; with no cache the best source format. Candidates are priority-ordered.
        val format = resolved.candidates.firstOrNull { it in resolved.cached }
            ?: resolved.candidates.firstOrNull { it in resolved.sourceFormats }
        val default = format?.let {
            val copy = resolved.cached[it]
            DefaultFormat(it, copy != null, copy?.sizeBytes ?: resolved.sourceFormats[it],
                copy != null && (it !in resolved.sourceFormats || copy.sourceAvailability == SourceAvailability.CONFIRMED_MISSING))
        }
        return BookRow(entry.key, entry.book.title, entry.book.authors, entry.book.hasCover, entry.read,
            resolved.cached.isNotEmpty(), default, if (default == null) NoFormatReason.NO_FORMAT else null)
    }

    private fun categoryValues(entry: Entry, categorization: Categorization): List<String> = when (categorization) {
        Categorization.None -> emptyList()
        Categorization.Series -> listOfNotNull(entry.book.series)
        Categorization.Tags -> entry.book.tags
        is Categorization.Column ->
            (entry.book.customValues[categorization.id.sourceId] as? ImportedColumnValue.Text)?.values.orEmpty()
    }.filter { it.isNotBlank() }.distinct()

    private fun newestFirst(collator: Collator): Comparator<Entry> =
        compareByDescending<Entry> { it.addedEpoch }.then(tieBreak(collator))

    private fun bookOrder(sort: BookSort, collator: Collator): Comparator<Entry> {
        val primary: Comparator<Entry> = when (sort.key) {
            BookSortKey.TITLE -> Comparator<Entry> { a, b -> collator.compare(a.book.title, b.book.title) }
                .let { if (sort.ascending) it else it.reversed() }
            BookSortKey.ADDED -> if (sort.ascending) compareBy<Entry> { it.addedEpoch } else compareByDescending<Entry> { it.addedEpoch }
            BookSortKey.RATING -> nullsLast(sort.ascending) { it.book.rating }
            BookSortKey.SERIES_INDEX -> nullsLast(sort.ascending) { it.book.seriesIndex }
        }
        return primary.then(tieBreak(collator))
    }

    private fun tieBreak(collator: Collator): Comparator<Entry> = Comparator { a, b ->
        collator.compare(a.book.title, b.book.title).takeIf { it != 0 }
            ?: a.book.title.compareTo(b.book.title).takeIf { it != 0 }
            ?: a.book.sourceId.compareTo(b.book.sourceId).takeIf { it != 0 }
            ?: a.book.sourceUuid.compareTo(b.book.sourceUuid)
    }

    private fun <T : Comparable<T>> nullsLast(ascending: Boolean, value: (Entry) -> T?): Comparator<Entry> =
        Comparator { a, b ->
            val x = value(a)
            val y = value(b)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                ascending -> x.compareTo(y)
                else -> y.compareTo(x)
            }
        }

    private fun priorityOrder(priority: List<BookFormat>): Comparator<BookFormat> =
        compareBy<BookFormat> { priority.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }.thenBy { it.value }

    private companion object {
        fun lower(value: String) = value.lowercase(Locale.ROOT)

        fun keywords(search: String): List<String> {
            val words = mutableListOf<String>()
            val current = StringBuilder()
            search.forEach { c ->
                if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    if (current.isNotEmpty()) words.add(current.toString()).also { current.clear() }
                } else current.append(c)
            }
            if (current.isNotEmpty()) words.add(current.toString())
            return words.map(::lower)
        }

        /** Source timestamps may omit an offset; those are read as UTC. Unknown time sorts as oldest. */
        fun epoch(value: String?): Long {
            if (value == null) return Long.MIN_VALUE
            val iso = value.replace(' ', 'T')
            return try {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { Long.MIN_VALUE }
            }
        }
    }
}
