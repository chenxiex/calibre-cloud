package io.github.chenxiex.calibrecloud.library

import io.github.chenxiex.calibrecloud.metadata.*
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import io.github.chenxiex.calibrecloud.storage.api.SourceAvailability
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryIndexTest {
    private val libraryId = LibraryId(UUID.randomUUID())
    private val identity = LibraryIdentity(libraryId, LibraryLocation.Local("a.documents", "tree"), UUID.randomUUID())
    private val epub = BookFormat.parse("EPUB")
    private val pdf = BookFormat.parse("PDF")
    private val mobi = BookFormat.parse("MOBI")
    private val readColumn = CustomColumnId(1, "#finished")
    private val topic = CustomColumnId(2, "#topic")
    private val shelf = CustomColumnId(3, "#shelf")
    private val columns = listOf(
        ImportedColumn(readColumn, "读完", "bool", false, true),
        ImportedColumn(topic, "主题", "text", true, true),
        ImportedColumn(shelf, "书架", "enumeration", false, true),
        ImportedColumn(CustomColumnId(4, "#pages"), "页数", "int", false, false),
    )

    private fun book(id: Long, title: String, authors: List<String> = listOf("作者"), added: String? = "2026-01-0${id}T00:00:00+00:00",
        rating: Int? = null, series: String? = null, index: Double? = null, tags: List<String> = emptyList(),
        comments: String = "", formats: List<Pair<BookFormat, Long?>> = listOf(epub to 100L),
        values: Map<Long, ImportedColumnValue> = emptyMap()) = ImportedBook(id, UUID(0, id), title, authors, added, rating,
        series, index, tags, comments, formats.map { ImportedFormat(it.first, it.second, RelativeSourcePath("p$id/b.${it.first.value.lowercase()}")) },
        RelativeSourcePath("p$id"), true, values)

    private fun library(books: List<ImportedBook>, status: CustomColumnId? = readColumn, generation: UUID = UUID.randomUUID()) =
        LibraryIndex(ImportedLibrary(identity, generation, 1, ParsedLibrary(null, books, columns), status,
            when {
                status == null -> ReadColumnStatus.NOT_CONFIGURED
                columns.any { it.id == status && it.datatype == "bool" } -> ReadColumnStatus.VALID
                else -> ReadColumnStatus.INVALID
            }))

    private fun copy(book: ImportedBook, format: BookFormat, size: Long? = 7, availability: SourceAvailability = SourceAvailability.AVAILABLE) =
        DownloadedCopy(CopyKey(BookKey(libraryId, book.sourceId, book.sourceUuid), format),
            CompleteCopyLocation(libraryId, UUID.randomUUID()), book.title, size, FileVersion(BackendKind.LOCAL, "v"), availability)

    private fun LibraryQueryResult.titles(): List<String> = (this as LibraryQueryResult.Books).rows.map { it.title }
    private fun LibraryQueryResult.folders() = (this as LibraryQueryResult.Folders).rows
    private val base = LibraryRequest(pageSize = 50)

    @Test
    fun searchRequiresEveryKeywordCaseInsensitivelyAcrossFieldsAndMatchesChinese() {
        val books = listOf(
            book(1, "Dune Messiah", authors = listOf("Frank Herbert"), tags = listOf("scifi"), comments = "沙漠 <b>行星</b>"),
            book(2, "Emma", authors = listOf("Jane Austen"), series = "Classics", values = mapOf(2L to ImportedColumnValue.Text(listOf("Romance")))),
        )
        val index = library(books)
        fun find(text: String, scope: SearchScope = SearchScope.All) = index.query(base.copy(search = text, searchScope = scope), emptyList()).titles()
        assertEquals(listOf("Dune Messiah"), find("dune HERBERT"))
        assertEquals(listOf("Dune Messiah"), find("  scifi　沙漠 "))
        assertEquals(emptyList<String>(), find("dune austen"))
        assertEquals(listOf("Emma"), find("romance"))
        assertEquals(listOf("Emma"), find("classics", SearchScope.Series))
        assertEquals(emptyList<String>(), find("classics", SearchScope.Title))
        assertEquals(listOf("Emma"), find("rom", SearchScope.Column(topic)))
        assertEquals(listOf("Dune Messiah"), find("scifi", SearchScope.Tags))
        assertEquals(listOf("Dune Messiah"), find("frank", SearchScope.Authors))
        // Literal matching: no wildcard or expression meaning, and unsupported columns are rejected.
        assertEquals(emptyList<String>(), find("d*ne"))
        assertEquals(emptyList<String>(), find("tags:scifi"))
        assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.SEARCH_COLUMN_INVALID),
            index.query(base.copy(search = "x", searchScope = SearchScope.Column(CustomColumnId(4, "#pages"))), emptyList()))
        assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.SEARCH_COLUMN_INVALID),
            index.query(base.copy(search = "x", searchScope = SearchScope.Column(readColumn)), emptyList()))
    }

    @Test
    fun searchCoversWholeLibraryAndIgnoresFolderButKeepsFilters() {
        val a = book(1, "Alpha", tags = listOf("one"))
        val b = book(2, "Beta", tags = listOf("two"), formats = listOf(pdf to null))
        val index = library(listOf(a, b))
        val inFolder = base.copy(categorization = Categorization.Tags, folder = FolderKey("one"))
        assertEquals(listOf("Beta"), index.query(inFolder.copy(search = "beta"), emptyList()).titles())
        assertEquals(emptyList<String>(), index.query(inFolder.copy(search = "beta", filters = LibraryFilters(formats = setOf(epub))), emptyList()).titles())
    }

    @Test
    fun filtersOrWithinDimensionAndAcrossDimensions() {
        val a = book(1, "A", formats = listOf(epub to 1L, pdf to 2L), values = mapOf(1L to ImportedColumnValue.Bool(true)))
        val b = book(2, "B", formats = listOf(epub to 1L), values = mapOf(1L to ImportedColumnValue.Bool(false)))
        val c = book(3, "C", formats = listOf(pdf to 2L))
        val index = library(listOf(a, b, c))
        val copies = listOf(copy(a, epub), copy(c, pdf))
        fun titles(filters: LibraryFilters) = index.query(base.copy(filters = filters, sort = BookSort(BookSortKey.TITLE, true)), copies).titles()
        assertEquals(listOf("A", "C"), titles(LibraryFilters(downloads = setOf(DownloadFilter.DOWNLOADED))))
        assertEquals(listOf("B"), titles(LibraryFilters(downloads = setOf(DownloadFilter.NOT_DOWNLOADED))))
        assertEquals(listOf("A", "B", "C"), titles(LibraryFilters(downloads = DownloadFilter.entries.toSet())))
        assertEquals(listOf("A"), titles(LibraryFilters(reads = setOf(ReadFilter.READ))))
        assertEquals(listOf("B", "C"), titles(LibraryFilters(reads = setOf(ReadFilter.UNREAD))))
        assertEquals(listOf("A", "B"), titles(LibraryFilters(formats = setOf(epub))))
        assertEquals(listOf("A", "B", "C"), titles(LibraryFilters(formats = setOf(epub, pdf))))
        assertEquals(listOf("A"), titles(LibraryFilters(downloads = setOf(DownloadFilter.DOWNLOADED), reads = setOf(ReadFilter.READ), formats = setOf(epub))))
        assertEquals(emptyList<String>(), titles(LibraryFilters(downloads = setOf(DownloadFilter.NOT_DOWNLOADED), reads = setOf(ReadFilter.READ))))
    }

    @Test
    fun downloadedMeansCompleteCopyInsideFormatScopeOnly() {
        val a = book(1, "A", formats = listOf(epub to 1L, pdf to 2L))
        val index = library(listOf(a))
        val onlyPdf = listOf(copy(a, pdf))
        val epubScope = base.copy(filters = LibraryFilters(downloads = setOf(DownloadFilter.DOWNLOADED), formats = setOf(epub)))
        assertEquals(emptyList<String>(), index.query(epubScope, onlyPdf).titles())
        val notDownloaded = base.copy(filters = LibraryFilters(downloads = setOf(DownloadFilter.NOT_DOWNLOADED), formats = setOf(epub)))
        assertEquals(listOf("A"), index.query(notDownloaded, onlyPdf).titles())
        val row = (index.query(base.copy(filters = LibraryFilters(formats = setOf(epub))), onlyPdf) as LibraryQueryResult.Books).rows.single()
        assertFalse(row.downloaded)
        assertEquals(epub, row.defaultFormat!!.format)
        // Without a format filter the PDF copy counts and is the default format despite EPUB priority.
        val unfiltered = (index.query(base, onlyPdf) as LibraryQueryResult.Books).rows.single()
        assertTrue(unfiltered.downloaded)
        assertEquals(DefaultFormat(pdf, cached = true, sizeBytes = 7, sourceMissing = false), unfiltered.defaultFormat)
        // Copies of other libraries never apply.
        val otherLibrary = LibraryId(UUID.randomUUID())
        val foreign = DownloadedCopy(CopyKey(BookKey(otherLibrary, 1, a.sourceUuid), pdf), CompleteCopyLocation(otherLibrary, UUID.randomUUID()),
            "A", 7, FileVersion(BackendKind.LOCAL, "v"), SourceAvailability.AVAILABLE)
        assertFalse((index.query(base, listOf(foreign)) as LibraryQueryResult.Books).rows.single().downloaded)
    }

    @Test
    fun readFilterIsUnavailableForUnconfiguredOrInvalidColumnInsteadOfTreatingEveryBookUnread() {
        val a = book(1, "A", values = mapOf(1L to ImportedColumnValue.Bool(true)))
        val filter = base.copy(filters = LibraryFilters(reads = setOf(ReadFilter.UNREAD)))
        listOf(null, CustomColumnId(9, "#gone"), CustomColumnId(2, "#topic")).forEach { column ->
            val index = library(listOf(a), column)
            assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.READ_FILTER_UNAVAILABLE), index.query(filter, emptyList()))
            assertNull((index.query(base, emptyList()) as LibraryQueryResult.Books).rows.single().read)
        }
        val valid = library(listOf(a, book(2, "B")))
        assertEquals(listOf(true, false), (valid.query(base.copy(sort = BookSort(BookSortKey.TITLE, true)), emptyList()) as LibraryQueryResult.Books).rows.map { it.read })
    }

    @Test
    fun tagsMakeMultipleFoldersAndUntaggedBooksFormOneFallbackAndEmptyResultsHaveNone() {
        val multi = book(1, "Multi", tags = listOf("b", "a", "a"), added = "2026-01-01T00:00:00+00:00")
        val newer = book(2, "Newer", tags = listOf("a"), added = "2026-01-05T00:00:00+00:00")
        val none = book(3, "None")
        val index = library(listOf(multi, newer, none))
        val roots = index.query(base.copy(categorization = Categorization.Tags), emptyList()).folders()
        assertEquals(listOf("a", "b", null), roots.map { it.key.name })
        assertEquals(listOf(2, 1, 1), roots.map { it.bookCount })
        assertEquals("Newer", roots[0].representative.title)
        assertEquals("Multi", roots[1].representative.title)
        assertEquals(listOf("Multi", "Newer"), index.query(base.copy(categorization = Categorization.Tags, folder = FolderKey("a"),
            sort = BookSort(BookSortKey.TITLE, true)), emptyList()).titles())
        assertEquals(listOf("None"), index.query(base.copy(categorization = Categorization.Tags, folder = FolderKey(null)), emptyList()).titles())
        // A filter that matches only tagged books leaves no fallback; no match leaves no folder at all.
        assertEquals(listOf("a", "b"), index.query(base.copy(categorization = Categorization.Tags, search = "", filters = LibraryFilters(
            downloads = setOf(DownloadFilter.DOWNLOADED))), listOf(copy(multi, epub))).folders().map { it.key.name })
        assertTrue(index.query(base.copy(categorization = Categorization.Tags, filters = LibraryFilters(downloads = setOf(DownloadFilter.DOWNLOADED))), emptyList()).folders().isEmpty())
        // Folder search flattens to books and ignores categorization.
        assertEquals(listOf("None"), index.query(base.copy(categorization = Categorization.Tags, search = "none"), emptyList()).titles())
    }

    @Test
    fun seriesAndColumnCategoriesRejectInvalidColumnsAndSortSeriesByIndexMissingLast() {
        val s1 = book(1, "S1", series = "Saga", index = 2.0)
        val s2 = book(2, "S2", series = "Saga", index = 1.0)
        val s3 = book(3, "S3", series = "Saga")
        val t = book(4, "T", values = mapOf(3L to ImportedColumnValue.Text(listOf("Fav")), 2L to ImportedColumnValue.Text(listOf("x", "y"))))
        val index = library(listOf(s1, s2, s3, t))
        val series = base.copy(categorization = Categorization.Series, folder = FolderKey("Saga"))
        assertEquals(listOf("S2", "S1", "S3"), index.query(series.copy(sort = BookSort.defaultFor(series.categorization, series.folder)), emptyList()).titles())
        assertEquals(listOf("S1", "S2", "S3"), index.query(series.copy(sort = BookSort(BookSortKey.SERIES_INDEX, false)), emptyList()).titles())
        assertEquals(listOf("Fav"), index.query(base.copy(categorization = Categorization.Column(shelf)), emptyList()).folders().map { it.key.name }.filterNotNull())
        assertEquals(listOf("x", "y"), index.query(base.copy(categorization = Categorization.Column(topic)), emptyList()).folders().map { it.key.name }.filterNotNull())
        listOf(readColumn, CustomColumnId(4, "#pages"), CustomColumnId(9, "#gone")).forEach {
            assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.CATEGORY_COLUMN_INVALID),
                index.query(base.copy(categorization = Categorization.Column(it)), emptyList()))
        }
    }

    @Test
    fun sortingIsStableDefaultsToNewestAndPutsMissingRatingsLast() {
        val books = listOf(
            book(1, "same", rating = 4, added = "2026-01-01T00:00:00+00:00"),
            book(2, "same", rating = 4, added = "2026-01-01T00:00:00+00:00"),
            book(3, "Zed", rating = null, added = "2026-01-03T00:00:00+00:00"),
            book(4, "apple", rating = 10, added = null),
        )
        val index = library(books)
        val ids = { r: LibraryQueryResult -> (r as LibraryQueryResult.Books).rows.map { it.key.sourceId } }
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(index.query(base, emptyList())))
        assertEquals(listOf(4L, 1L, 2L, 3L), ids(index.query(base.copy(sort = BookSort(BookSortKey.RATING, false)), emptyList())))
        assertEquals(listOf(1L, 2L, 4L, 3L), ids(index.query(base.copy(sort = BookSort(BookSortKey.RATING, true)), emptyList())))
        assertEquals(listOf(4L, 1L, 2L, 3L), ids(index.query(base.copy(sort = BookSort(BookSortKey.TITLE, true)), emptyList())))
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(index.query(base.copy(sort = BookSort(BookSortKey.TITLE, false)), emptyList())))
    }

    @Test
    fun pagesAreStableSlicesWithTotalsAndLastPagePartial() {
        val index = library((1L..7L).map { book(it, "B$it") })
        val ids = { r: LibraryQueryResult -> (r as LibraryQueryResult.Books).rows.map { it.key.sourceId } }
        val all = ids(index.query(base, emptyList()))
        val pages = (0 until 7 step 3).map { index.query(base.copy(offset = it, pageSize = 3), emptyList()) as LibraryQueryResult.Books }
        assertEquals(all, pages.flatMap { p -> p.rows.map { it.key.sourceId } })
        assertEquals(listOf(3, 3, 1), pages.map { it.rows.size })
        assertTrue(pages.all { it.total == 7 })
        assertTrue((index.query(base.copy(offset = 9, pageSize = 3), emptyList()) as LibraryQueryResult.Books).rows.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { base.copy(pageSize = 0) }
    }

    @Test
    fun defaultFormatFollowsPriorityCacheFirstThenSourceAndMissingAuthorsAndSizesStayEmpty() {
        val a = book(1, "A", formats = listOf(pdf to null, mobi to 5L, epub to 10L))
        val none = book(2, "None", authors = emptyList(), formats = emptyList())
        val gone = book(3, "Gone", formats = listOf(epub to 1L))
        val index = library(listOf(a, none, gone))
        fun row(id: Long, request: LibraryRequest, copies: List<DownloadedCopy> = emptyList()) =
            (index.query(request, copies) as LibraryQueryResult.Books).rows.single { it.key.sourceId == id }
        assertEquals(DefaultFormat(epub, false, 10, false), row(1, base).defaultFormat)
        assertEquals(pdf, row(1, base.copy(formatPriority = listOf(pdf, epub))).defaultFormat!!.format)
        assertNull(row(1, base.copy(formatPriority = listOf(pdf))).defaultFormat!!.sizeBytes)
        assertEquals(mobi, row(1, base.copy(formatPriority = emptyList(), filters = LibraryFilters(formats = setOf(mobi, pdf)))).defaultFormat!!.format)
        // Unlisted formats follow by name: EPUB, MOBI, PDF.
        assertEquals(epub, row(1, base.copy(formatPriority = emptyList())).defaultFormat!!.format)
        // A cached lower-priority format wins over an uncached higher one.
        assertEquals(DefaultFormat(mobi, true, 5, false), row(1, base, listOf(copy(a, mobi, size = null))).defaultFormat)
        assertEquals(NoFormatReason.NO_FORMAT, row(2, base).noFormat)
        assertNull(row(2, base).defaultFormat)
        // A missing author stays an empty list; nothing is substituted.
        assertTrue(row(2, base).authors.isEmpty())
        assertEquals(libraryId, row(2, base).key.libraryId)
        // A cached copy whose source format vanished, or whose source is confirmed missing, is flagged.
        assertEquals(DefaultFormat(epub, true, 1, true), row(3, base, listOf(copy(gone, pdf, 9), copy(gone, epub, 1, SourceAvailability.CONFIRMED_MISSING))).defaultFormat)
        assertEquals(DefaultFormat(pdf, true, 9, true), row(3, base, listOf(copy(gone, pdf, 9))).defaultFormat)
        assertTrue(row(3, base, listOf(copy(gone, epub, 1, SourceAvailability.CONFIRMED_MISSING))).defaultFormat!!.sourceMissing)
    }

    @Test
    fun staleExpectationIsRejectedAfterNewGenerationOrReadColumnChange() {
        val first = library(listOf(book(1, "A")))
        val expected = first.revision
        assertTrue(first.query(base.copy(expected = expected), emptyList()) is LibraryQueryResult.Books)
        val reimported = library(listOf(book(1, "A")))
        assertEquals(LibraryQueryResult.Stale, reimported.query(base.copy(expected = expected), emptyList()))
        val reconfigured = LibraryIndex(ImportedLibrary(identity, expected.generation, 1, ParsedLibrary(null, listOf(book(1, "A")), columns), null, ReadColumnStatus.NOT_CONFIGURED))
        assertEquals(LibraryQueryResult.Stale, reconfigured.query(base.copy(expected = expected), emptyList()))
    }
}
