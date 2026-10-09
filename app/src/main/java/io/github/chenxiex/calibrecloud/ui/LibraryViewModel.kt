package io.github.chenxiex.calibrecloud.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.library.BookRow
import io.github.chenxiex.calibrecloud.library.BookSort
import io.github.chenxiex.calibrecloud.library.BookSortKey
import io.github.chenxiex.calibrecloud.library.Categorization
import io.github.chenxiex.calibrecloud.library.FolderKey
import io.github.chenxiex.calibrecloud.library.FolderRow
import io.github.chenxiex.calibrecloud.library.LibraryFilters
import io.github.chenxiex.calibrecloud.library.LibraryOverview
import io.github.chenxiex.calibrecloud.library.LibraryProblem
import io.github.chenxiex.calibrecloud.library.LibraryQueryResult
import io.github.chenxiex.calibrecloud.library.LibraryQueryService
import io.github.chenxiex.calibrecloud.library.LibraryRequest
import io.github.chenxiex.calibrecloud.library.ReadMarkChoice
import io.github.chenxiex.calibrecloud.library.SelectionExpansion
import io.github.chenxiex.calibrecloud.library.SelectionResult
import io.github.chenxiex.calibrecloud.library.SearchScope
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.state.LibrarySelection
import io.github.chenxiex.calibrecloud.state.SearchHistoryStore
import io.github.chenxiex.calibrecloud.storage.covers.CoverRepository
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskEvent
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.covers.CoverService
import io.github.chenxiex.calibrecloud.tasks.persistence.DurableTaskQueue
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

enum class LibraryViewMode { GRID, LIST }

/** What the library area shows; page text is resolved from resources by the screen. */
sealed interface LibraryContent {
    data object Loading : LibraryContent
    /** No library has been chosen yet. */
    data object Unconfigured : LibraryContent
    /** A library is chosen but no complete import exists. */
    data object NoMetadata : LibraryContent
    data class Problem(val problem: LibraryProblem) : LibraryContent
    data class Books(val total: Int, val offset: Int, val rows: List<BookRow>) : LibraryContent
    data class Folders(val total: Int, val offset: Int, val rows: List<FolderRow>) : LibraryContent
}

/**
 * One visit of the search page. View, sort and filters start as the library's and changes stay here;
 * [query] is the last executed search, null until the search key is pressed.
 */
data class SearchSession(
    val viewMode: LibraryViewMode,
    val sort: BookSort,
    val filters: LibraryFilters,
    val scope: SearchScope = SearchScope.All,
    val query: String? = null,
)

/** Cover access of the visible page; the production implementation uses the cover cache and task queue. */
interface LibraryCovers {
    suspend fun read(book: BookKey): Bitmap?

    /** Enqueues (or reuses) the low-priority load of one missing cover; null when it was rejected. */
    suspend fun request(book: BookKey, selectionToken: UUID): TaskId?

    /** Suspends until the task has finished in any way. */
    suspend fun awaitFinished(task: TaskId)

    suspend fun wake()
}

class QueueLibraryCovers(
    private val repository: CoverRepository,
    private val service: CoverService,
    private val queue: DurableTaskQueue,
    private val coordinator: TaskCoordinator,
) : LibraryCovers {
    override suspend fun read(book: BookKey) = repository.read(book)

    override suspend fun request(book: BookKey, selectionToken: UUID): TaskId? = when (val result = service.submit(book, selectionToken)) {
        is SubmissionResult.Created -> result.taskId
        is SubmissionResult.Reused -> result.taskId
        is SubmissionResult.Promoted -> result.taskId
        is SubmissionResult.Rejected -> null
    }

    override suspend fun awaitFinished(task: TaskId) {
        queue.observe(task).first { it.state is TaskState.Finished }
    }

    override suspend fun wake() = coordinator.requestRun()
}

/**
 * View state and paging of the library page. Offsets are derived from [firstVisible] and the
 * measured [capacity], so a resize keeps the first visible item on the shown page. Queries run on
 * the query service's dispatcher and never contact a backend; only the covers of the shown page are
 * enqueued, once per displayed page, and a result for an older request, import or selection is dropped.
 *
 * While [search] is open the page shows the search session instead: the library's categorization,
 * folder and page stay as they were and are shown again by [closeSearch]. Only executed queries enter
 * the per-library history.
 *
 * Selection mode ([selected]) collects books and folders of the shown level only; it ends with
 * [finishSelection], a submitted batch action or a change of library. Every action expands the
 * selection again under the shown search and filters and acts on that frozen result (R26).
 */
class LibraryViewModel(
    private val selection: suspend () -> LibrarySelection?,
    private val queries: LibraryQueryService,
    private val covers: LibraryCovers,
    private val events: Flow<TaskEvent>,
    private val historyStore: SearchHistoryStore,
    private val batch: LibraryBatch,
    private val formatPriority: suspend () -> List<BookFormat> = { LibraryRequest.DEFAULT_FORMAT_PRIORITY },
    /** State of the unfinished library sync of the selection with this token, or null when none is under way. */
    private val syncState: suspend (UUID) -> TaskState? = { null },
) : ViewModel() {
    var content by mutableStateOf<LibraryContent>(LibraryContent.Loading)
        private set
    /** While the library has no metadata: the sync under way (such as the first one after adding it), or null. */
    var syncing by mutableStateOf<TaskState?>(null)
        private set
    var overview by mutableStateOf<LibraryOverview?>(null)
        private set
    private var libraryViewMode by mutableStateOf(LibraryViewMode.GRID)
    val viewMode: LibraryViewMode get() = search?.viewMode ?: libraryViewMode
    var categorization by mutableStateOf<Categorization>(Categorization.None)
        private set
    /** The open folder; null at the root. */
    var folder by mutableStateOf<FolderKey?>(null)
        private set
    var firstVisible by mutableIntStateOf(0)
        private set
    /** Backend of the chosen library, or null while none is chosen; picks the matching sync entry. */
    var backend by mutableStateOf<BackendKind?>(null)
        private set
    var capacity by mutableIntStateOf(0)
        private set
    private var rootSort by mutableStateOf(BookSort.Default)
    private var folderSort by mutableStateOf(BookSort.Default)
    val sort: BookSort get() = search?.sort ?: if (folder != null) folderSort else rootSort
    /** Name direction of the folder level; the fallback folder stays last either way. */
    var foldersAscending by mutableStateOf(true)
        private set
    private var libraryFilters by mutableStateOf(LibraryFilters())
    /** Filters of the shown page: the search session's while it is open, otherwise the library's. */
    val filters: LibraryFilters get() = search?.filters ?: libraryFilters

    /** The open search page; null while the library is shown. */
    var search by mutableStateOf<SearchSession?>(null)
        private set
    /** Text of the search input; kept here so it survives Activity recreation and starts empty per visit. */
    var searchInput by mutableStateOf("")
        private set
    /** Executed queries of the current library, newest first. */
    var history by mutableStateOf<List<String>>(emptyList())
        private set

    /** The library page lists folders: a categorized root outside the search page. */
    val showsFolders: Boolean get() = search == null && folder == null && categorization != Categorization.None

    val coverImages = mutableStateMapOf<BookKey, Bitmap>()

    /** The selection while selection mode is on; null otherwise. */
    var selected by mutableStateOf<LibrarySelectionSet?>(null)
        private set
    /** Distinct books the selection expands to now; null while unknown. */
    var selectedBooks by mutableStateOf<Int?>(null)
        private set
    /** The read-state operation R26 offers for the selection; null while unknown. */
    var readMark by mutableStateOf<ReadMarkChoice?>(null)
        private set
    var removal by mutableStateOf<RemovalConfirmation?>(null)
        private set
    var notice by mutableStateOf<BatchNotice?>(null)
        private set
    /** A batch action is running; further actions wait for it. */
    var batchBusy by mutableStateOf(false)
        private set
    private var selectionOwner: UUID? = null
    private var expanding: Job? = null
    /** The saved format order, reread on every load so a change in settings applies on return. */
    private var priority = LibraryRequest.DEFAULT_FORMAT_PRIORITY

    private var visible = false
    private var loadGeneration = 0L
    private var loading: Job? = null
    private var listening: Job? = null
    private var rootFirstVisible = 0
    private var libraryFirstVisible = 0
    private val requested = mutableSetOf<BookKey>()
    private val waiting = mutableMapOf<BookKey, Job>()
    private var pageKey: Any? = null
    private var pageBooks: Set<BookKey> = emptySet()
    private var coverToken: UUID? = null

    fun setVisible(value: Boolean) {
        visible = value
        listening?.cancel()
        if (!value) {
            loading?.cancel()
            loadGeneration++
            cancelCoverWaits()
            return
        }
        listening = viewModelScope.launch {
            events.collect { event ->
                // Cover publications only change images, which the awaiting coroutines refresh themselves.
                if (event is TaskEvent.CacheChanged && event.request !is TaskRequest.CoverLoad) reload()
                // Without metadata the page shows the sync under way, so its changes are followed too.
                if (event is TaskEvent.Changed && content == LibraryContent.NoMetadata && event.record.isLibrarySync()) reload()
            }
        }
        reload()
    }

    fun refresh() {
        if (visible) reload()
    }

    fun onMeasured(value: Int) {
        if (value <= 0 || value == capacity) return
        capacity = value
        reload()
    }

    /** Shows the page with the given index, clamped to the existing pages of the shown result. */
    fun showPage(index: Int) {
        val total = when (val shown = content) {
            is LibraryContent.Books -> shown.total
            is LibraryContent.Folders -> shown.total
            else -> return
        }
        if (capacity <= 0) return
        val start = (index.coerceAtLeast(0).toLong() * capacity).coerceAtMost(lastPageStart(total, capacity).toLong()).toInt()
        if (start == pageStart(firstVisible, capacity)) return
        firstVisible = start
        reload()
    }

    fun showAs(mode: LibraryViewMode) {
        if (mode == viewMode) return
        val session = search
        if (session != null) search = session.copy(viewMode = mode) else libraryViewMode = mode
        // The grid and list capacities differ; the first visible item stays on the shown page.
        reload()
    }

    fun categorize(value: Categorization) {
        if (value == categorization && folder == null) return
        finishSelection()
        categorization = value
        folder = null
        firstVisible = 0
        reload()
    }

    fun openFolder(key: FolderKey) {
        finishSelection()
        rootFirstVisible = pageStart(firstVisible, capacity)
        folder = key
        folderSort = BookSort.defaultFor(categorization, key)
        firstVisible = 0
        reload()
    }

    /** Returns false at the root so the caller can fall through to the system back action. */
    fun closeFolder(): Boolean {
        if (folder == null) return false
        finishSelection()
        folder = null
        firstVisible = rootFirstVisible
        reload()
        return true
    }

    /** A new key starts in its default direction; choosing the shown key again reverses it. */
    fun sortBy(key: BookSortKey) {
        val current = sort
        val value = if (current.key == key) current.copy(ascending = !current.ascending) else BookSort.of(key)
        val session = search
        when {
            session != null -> search = session.copy(sort = value)
            folder != null -> folderSort = value
            else -> rootSort = value
        }
        firstVisible = 0
        reload()
    }

    /** Folders only sort by name; choosing it again reverses the direction. */
    fun reverseFolders() {
        foldersAscending = !foldersAscending
        firstVisible = 0
        reload()
    }

    /** Changes the filters of the shown page and starts again from its first page. */
    fun updateFilters(change: (LibraryFilters) -> LibraryFilters) {
        finishSelection()
        val session = search
        if (session != null) search = session.copy(filters = change(session.filters)) else libraryFilters = change(libraryFilters)
        firstVisible = 0
        reload()
    }

    /** Opens the search page with the library's view, book sort and filters; the library position is kept. */
    fun openSearch() {
        if (search != null) return
        finishSelection()
        libraryFirstVisible = pageStart(firstVisible, capacity)
        // A flat result has no series to order by.
        search = SearchSession(viewMode, sort.takeIf { it.key != BookSortKey.SERIES_INDEX } ?: BookSort.Default, filters)
        searchInput = ""
        firstVisible = 0
        reload()
    }

    /** Returns false when no search is open so the caller can fall through to the system back action. */
    fun closeSearch(): Boolean {
        if (search == null) return false
        finishSelection()
        search = null
        firstVisible = libraryFirstVisible
        reload()
        return true
    }

    /** Limits the next executed query to one field; it does not run a search by itself. */
    fun chooseScope(scope: SearchScope) {
        search = search?.copy(scope = scope)
    }

    /** Editing never runs a search; emptying the input returns to the history. */
    fun editSearch(text: String) {
        searchInput = text
        if (text.isBlank()) clearQuery()
    }

    /** Runs [text] as an executed query and saves it to the history; blank text is ignored. */
    fun submitSearch(text: String) {
        val session = search ?: return
        val query = text.trim()
        if (query.isEmpty()) return
        finishSelection()
        searchInput = text
        search = session.copy(query = query)
        firstVisible = 0
        reload()
        viewModelScope.launch {
            val id = selection()?.identity?.id ?: return@launch
            try {
                historyStore.record(id, query)
                history = historyStore.list(id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // The search still ran; the history shows what was saved.
            }
        }
    }

    private fun clearQuery() {
        val session = search ?: return
        if (session.query == null) return
        finishSelection()
        search = session.copy(query = null)
        firstVisible = 0
        reload()
    }

    fun clearHistory() {
        viewModelScope.launch {
            val id = selection()?.identity?.id ?: return@launch
            try {
                historyStore.clear(id)
                history = historyStore.list(id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // The remaining history stays visible.
            }
        }
    }

    /** A long press starts selection with the book; in selection mode a tap adds or removes it. */
    fun toggleBook(key: BookKey) = toggle { it.copy(books = it.books.toggle(key)) }

    fun toggleFolder(key: FolderKey) = toggle { it.copy(folders = it.folders.toggle(key)) }

    private fun toggle(change: (LibrarySelectionSet) -> LibrarySelectionSet) {
        if (batchBusy || removal != null) return
        val owner = coverToken ?: return
        val current = selected
        if (current == null) {
            selectionOwner = owner
            notice = null
        }
        selected = change(current ?: LibrarySelectionSet())
        expandSelection()
    }

    /** Leaves selection mode; the selection is dropped. */
    fun finishSelection() {
        expanding?.cancel()
        selected = null
        selectedBooks = null
        readMark = null
        removal = null
        selectionOwner = null
    }

    /** Called once [notice] has been handed to the system notification. */
    fun noticeHandled(shown: BatchNotice) {
        if (notice === shown) notice = null
    }

    /**
     * Submits a user download of each selected book's batch format (R24): books whose format already
     * has a complete copy are skipped and books without a format are counted, never submitted. No
     * reader is opened. Selection ends once anything was submitted.
     */
    fun downloadSelection() = runBatch { expansion, owner ->
        var submitted = 0
        var noFormat = 0
        var rejected = 0
        expansion.books.forEach { book ->
            val format = book.download
            when {
                format == null -> noFormat++
                book.downloaded -> Unit
                batch.download(CopyKey(book.key, format), owner) -> submitted++
                else -> rejected++
            }
        }
        if (submitted > 0) {
            try { batch.wake() } catch (failure: CancellationException) { throw failure } catch (_: Exception) { }
            finishSelection()
        }
        if (noFormat > 0 || rejected > 0) notice = BatchNotice.Downloads(noFormat, rejected)
    }

    /** Freezes the selected books and the current format filter (none means all formats) for confirmation. */
    fun prepareRemoval() = runBatch { expansion, _ ->
        val formats = filters.formats.takeIf { it.isNotEmpty() }
        val plan = batch.previewRemoval(expansion.books.map { it.key }.toSet(), formats)
        if (plan == null) notice = BatchNotice.Unavailable
        else removal = RemovalConfirmation(plan, expansion.books.size, formats)
    }

    fun cancelRemoval() {
        if (!batchBusy) removal = null
    }

    /** Executes the confirmed plan as frozen; a failure keeps the selection. */
    fun confirmRemoval() {
        val shown = removal ?: return
        if (batchBusy) return
        batchBusy = true
        viewModelScope.launch {
            val removed = try {
                batch.remove(shown.plan)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                false
            }
            removal = null
            batchBusy = false
            if (removed) finishSelection() else notice = BatchNotice.RemovalFailed
            reload()
        }
    }

    private fun runBatch(action: suspend (SelectionExpansion, UUID) -> Unit) {
        val set = selected ?: return
        val owner = selectionOwner ?: return
        if (batchBusy || removal != null) return
        batchBusy = true
        notice = null
        viewModelScope.launch {
            try {
                val expansion = expand(set)
                when {
                    expansion == null -> notice = BatchNotice.Unavailable
                    expansion.books.isEmpty() -> notice = BatchNotice.NoBooks
                    else -> action(expansion, owner)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                notice = BatchNotice.Unavailable
            } finally {
                batchBusy = false
            }
        }
    }

    /** Recounts the selection; called on every change and after each page load. */
    private fun expandSelection() {
        val set = selected ?: return
        expanding?.cancel()
        expanding = viewModelScope.launch {
            val expansion = try {
                expand(set)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
            if (selected != set) return@launch
            selectedBooks = expansion?.books?.size
            readMark = expansion?.let { ReadMarkChoice.of(it, batch.readWriteAvailable) }
        }
    }

    /** The selection under the shown level's search and filters, or null when that level cannot be read. */
    private suspend fun expand(set: LibrarySelectionSet): SelectionExpansion? {
        if (selection()?.token != selectionOwner) return null
        repeat(MAX_ATTEMPTS) {
            val current = queries.overview() ?: return null
            when (val result = queries.expand(levelRequest(0, 1, current), set.folders, set.books)) {
                SelectionResult.Stale -> return@repeat
                is SelectionResult.Unavailable -> return null
                is SelectionResult.Expanded -> return result.expansion
            }
        }
        return null
    }

    /** The request of the shown level; a selection is expanded with the same search, filters and categorization. */
    private fun levelRequest(offset: Int, pageSize: Int, overview: LibraryOverview) = LibraryRequest(
        search = search?.query.orEmpty(), searchScope = search?.scope ?: SearchScope.All,
        categorization = categorization, folder = folder, sort = sort, foldersAscending = foldersAscending, filters = filters,
        formatPriority = priority, offset = offset, pageSize = pageSize, expected = overview.revision,
    )

    private fun reload() {
        if (!visible || capacity <= 0) return
        val generation = ++loadGeneration
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                load(generation)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // A local read failure keeps the previous page; the next refresh retries.
            }
        }
    }

    private suspend fun load(generation: Long) {
        val selected = selection()
        if (selected?.location == null) {
            if (generation != loadGeneration) return
            backend = null
            overview = null
            content = LibraryContent.Unconfigured
            syncing = null
            finishSelection()
            resetCovers(null)
            return
        }
        backend = selected.backend
        priority = formatPriority()
        repeat(MAX_ATTEMPTS) {
            val current = queries.overview()
            if (current == null) {
                if (generation != loadGeneration) return
                val sync = syncState(selected.token)
                if (generation != loadGeneration) return
                overview = null
                content = LibraryContent.NoMetadata
                syncing = sync
                finishSelection()
                resetCovers(selected.token)
                return
            }
            val session = search
            if (session != null && session.query == null) {
                // The search page shows its field choices and history until a query is executed.
                val saved = selected.identity?.let { historyStore.list(it.id) }.orEmpty()
                if (selection()?.token != selected.token || generation != loadGeneration) return
                overview = current
                history = saved
                // A field of a column that is no longer supported is not offered any more.
                val scope = session.scope
                if (scope is SearchScope.Column && current.categoryColumns.none { it.id == scope.id }) {
                    search = search?.copy(scope = SearchScope.All)
                }
                return
            }
            val request = levelRequest(pageStart(firstVisible, capacity), capacity, current)
            val shown: LibraryContent = when (val result = queries.query(request)) {
                LibraryQueryResult.Stale -> return@repeat
                is LibraryQueryResult.Unavailable ->
                    if (result.problem == LibraryProblem.NO_METADATA) LibraryContent.NoMetadata else LibraryContent.Problem(result.problem)
                is LibraryQueryResult.Books -> LibraryContent.Books(result.total, result.offset, result.rows)
                is LibraryQueryResult.Folders -> LibraryContent.Folders(result.total, result.offset, result.rows)
            }
            val total = (shown as? LibraryContent.Books)?.total ?: (shown as? LibraryContent.Folders)?.total
            if (total != null && total > 0 && request.offset >= total) {
                // The library shrank below the remembered page: show the last page instead.
                firstVisible = lastPageStart(total, capacity)
                return@repeat
            }
            if (selection()?.token != selected.token || generation != loadGeneration) return
            val sync = if (shown == LibraryContent.NoMetadata) syncState(selected.token) else null
            if (selection()?.token != selected.token || generation != loadGeneration) return
            overview = current
            content = shown
            syncing = sync
            if (coverToken != selected.token) resetCovers(selected.token)
            // A selection belongs to one library; an import or copy change only recounts it.
            if (selectionOwner != null && selectionOwner != selected.token) finishSelection() else expandSelection()
            val key = listOf(selected.token, current.revision, request.search, request.searchScope, request.filters, categorization, folder,
                request.sort, request.foldersAscending, request.offset)
            if (key != pageKey) {
                pageKey = key
                requested.clear()
            }
            loadCovers(shown, selected.token, generation)
            return
        }
    }

    private fun resetCovers(token: UUID?) {
        cancelCoverWaits()
        coverImages.clear()
        pageKey = null
        pageBooks = emptySet()
        coverToken = token
    }

    private fun cancelCoverWaits() {
        waiting.values.forEach { it.cancel() }
        waiting.clear()
    }

    private fun loadCovers(shown: LibraryContent, token: UUID, generation: Long) {
        val rows: List<BookRow> = when (shown) {
            is LibraryContent.Books -> shown.rows
            is LibraryContent.Folders -> shown.rows.map { it.representative }
            else -> emptyList()
        }
        val keys = rows.map { it.key }.toSet()
        pageBooks = keys
        coverImages.keys.retainAll(keys)
        waiting.keys.filter { it !in keys }.forEach { waiting.remove(it)?.cancel() }
        viewModelScope.launch {
            var submitted = false
            for (row in rows.distinctBy { it.key }) {
                if (row.key in coverImages) continue
                try {
                    val cached = covers.read(row.key)
                    if (generation != loadGeneration) return@launch
                    if (cached != null) {
                        coverImages[row.key] = cached
                        continue
                    }
                    if (!row.hasCover || !requested.add(row.key)) continue
                    val task = covers.request(row.key, token) ?: continue
                    if (generation != loadGeneration) return@launch
                    submitted = true
                    waiting[row.key] = viewModelScope.launch {
                        covers.awaitFinished(task)
                        if (row.key in pageBooks && coverToken == token) covers.read(row.key)?.let { coverImages[row.key] = it }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    // The title placeholder stays.
                }
            }
            if (submitted) try { covers.wake() } catch (failure: CancellationException) { throw failure } catch (_: Exception) { }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
                return modelClass.cast(LibraryViewModel(
                    dependencies.state::current, dependencies.libraryQuery,
                    QueueLibraryCovers(dependencies.covers, dependencies.coverService, dependencies.taskQueue, dependencies.taskCoordinator),
                    dependencies.taskQueue.events, dependencies.searchHistory,
                    QueueLibraryBatch(dependencies.copyService, dependencies.taskCoordinator, dependencies.maintenance),
                    dependencies.state::formatPriority,
                    { token -> dependencies.taskQueue.latestLibrarySync(token)?.state?.takeUnless { it is TaskState.Finished } },
                ))!!
            }
        }
    }
}

private fun TaskRecord.isLibrarySync() =
    (submission.request as? TaskRequest.CandidateConfiguration)?.operation == TaskRequest.CandidateConfiguration.LIBRARY_SYNC

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
