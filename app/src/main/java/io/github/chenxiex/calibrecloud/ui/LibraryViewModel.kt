package io.github.chenxiex.calibrecloud.ui

import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.map
import io.github.chenxiex.calibrecloud.tasks.background.CpuAwake
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
import io.github.chenxiex.calibrecloud.library.ReadMarkAction
import io.github.chenxiex.calibrecloud.library.ReadMarkBlock
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
import io.github.chenxiex.calibrecloud.tasks.api.PendingRead
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

    /** Enqueues (or reuses) the low-priority batch of the shown page's missing covers; null when it was rejected. */
    suspend fun request(books: List<BookKey>, selectionToken: UUID): TaskId?

    /** Every change of the batch, ending once it has finished in any way. */
    fun changes(task: TaskId): Flow<TaskState>

    suspend fun wake()
}

class QueueLibraryCovers(
    private val repository: CoverRepository,
    private val service: CoverService,
    private val queue: DurableTaskQueue,
    private val coordinator: TaskCoordinator,
) : LibraryCovers {
    override suspend fun read(book: BookKey) = repository.read(book)

    override suspend fun request(books: List<BookKey>, selectionToken: UUID): TaskId? = when (val result = service.submit(books, selectionToken)) {
        is SubmissionResult.Created -> result.taskId
        is SubmissionResult.Reused -> result.taskId
        is SubmissionResult.Promoted -> result.taskId
        is SubmissionResult.Rejected -> null
    }

    override fun changes(task: TaskId): Flow<TaskState> =
        queue.observe(task).map { it.state }.transformWhile { emit(it); it !is TaskState.Finished }

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
 *
 * [pendingReads] are the read status targets still being written (R13, R27), read from the queue
 * whenever a write or library sync changes and shown in place of the read marks. A target ends with the
 * sync that imports its result, so its end is shown only together with a page reread after it; they never enter
 * the query, so filters, search and sorting keep the import. A write failure that appears produces a
 * [readFailure] notice; the failures shown are dismissed when the page is left.
 *
 * The library's view mode, categorization, root sort, folder direction and filters are restored from
 * [viewStore] before the first page loads and saved on every change (R21, Q82); search session
 * changes are not saved. A change made before the restore finished wins over the saved view.
 */
class LibraryViewModel(
    private val selection: suspend () -> LibrarySelection?,
    private val queries: LibraryQueryService,
    private val covers: LibraryCovers,
    private val events: Flow<TaskEvent>,
    private val historyStore: SearchHistoryStore,
    private val batch: LibraryBatch,
    private val formatPriority: suspend () -> List<BookFormat> = { LibraryRequest.DEFAULT_FORMAT_PRIORITY },
    /** Where the library's view and filters are kept across launches. */
    private val viewStore: LibraryViewStore = LibraryViewStore.None,
    /** Keeps the CPU running while a page load or a finished cover's read is under way. */
    private val keepAwake: suspend (suspend () -> Unit) -> Unit = { it() },
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
    /** Books of the selected library whose read status target is still being written or failed to be. */
    var pendingReads by mutableStateOf<Map<BookKey, PendingRead>>(emptyMap())
        private set
    /** Read-state writes that newly failed, for one replaceable system notification. */
    var readFailure by mutableStateOf<ReadFailureNotice?>(null)
        private set
    /** The selection whose [pendingReads] are shown; failures already in them when it was first read are not notified again. */
    private var pendingOwner: UUID? = null
    private var pendingGeneration = 0L
    /** Dismissing the failures of a left page; rereads wait for it so they cannot bring the failures back. */
    private var dismissing: Job? = null
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
    /** The batches of the shown page being followed, each showing its covers as they are published. */
    private val waiting = mutableMapOf<TaskId, Job>()
    private var pageKey: Any? = null
    private var pageBooks: Set<BookKey> = emptySet()
    private var coverToken: UUID? = null
    /** The saved view being restored; loads wait for it. */
    private val restoring: Job
    private var viewChanged = false
    private var saving: Job? = null

    init {
        restoring = viewModelScope.launch {
            val saved = try {
                viewStore.load()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // An unreadable setting starts with the defaults.
                null
            }
            if (saved != null && !viewChanged) {
                libraryViewMode = saved.viewMode
                categorization = saved.categorization
                rootSort = saved.sort
                foldersAscending = saved.foldersAscending
                libraryFilters = saved.filters
            }
        }
        // A write can fail while another tab is shown; its notification should not wait for the page.
        viewModelScope.launch {
            events.collect { event ->
                if (event !is TaskEvent.Changed) return@collect
                when {
                    // A sync publishes its import as it ends: the shown page is reread with the pending targets,
                    // so a target never disappears while the older import is still shown.
                    event.record.isLibrarySync() && visible && event.record.state is TaskState.Finished -> reload()
                    event.record.submission.request is TaskRequest.ReadStatusWrite || event.record.isLibrarySync() -> refreshPending()
                }
            }
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        listening?.cancel()
        if (!value) {
            loading?.cancel()
            loadGeneration++
            cancelCoverWaits()
            dismissShownFailures()
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
        if (session != null) search = session.copy(viewMode = mode) else {
            libraryViewMode = mode
            saveView()
        }
        // The grid and list capacities differ; the first visible item stays on the shown page.
        reload()
    }

    fun categorize(value: Categorization) {
        if (value == categorization && folder == null) return
        finishSelection()
        categorization = value
        folder = null
        firstVisible = 0
        saveView()
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
            else -> {
                rootSort = value
                saveView()
            }
        }
        firstVisible = 0
        reload()
    }

    /** Folders only sort by name; choosing it again reverses the direction. */
    fun reverseFolders() {
        foldersAscending = !foldersAscending
        firstVisible = 0
        saveView()
        reload()
    }

    /** Changes the filters of the shown page and starts again from its first page. */
    fun updateFilters(change: (LibraryFilters) -> LibraryFilters) {
        finishSelection()
        val session = search
        if (session != null) search = session.copy(filters = change(session.filters)) else {
            libraryFilters = change(libraryFilters)
            saveView()
        }
        firstVisible = 0
        reload()
    }

    /** Clears every filter of the shown page, as the filter banner's close icon does (R24, Q83). */
    fun clearFilters() {
        if (filters != LibraryFilters()) updateFilters { LibraryFilters() }
    }

    /** Saves the library's (not the search session's) view; saves run in the order of the changes. */
    private fun saveView() {
        viewChanged = true
        val view = SavedLibraryView(libraryViewMode, categorization, rootSort, foldersAscending, libraryFilters)
        val previous = saving
        saving = viewModelScope.launch {
            previous?.join()
            try {
                viewStore.save(view)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // The shown view stays; the next change saves again.
            }
        }
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

    /** Called once [readFailure] has been handed to the system notification. */
    fun readFailureHandled(shown: ReadFailureNotice) {
        if (readFailure === shown) readFailure = null
    }

    /**
     * Submits the shown read-state mark (R14, R26) with its explicit target for the selection expanded
     * again now; the target is the one the user chose, never recomputed from a later state. The page's
     * read marks do not change: the books show the pending target until the sync after the write ends.
     */
    fun markSelection(action: ReadMarkAction) = runBatch { expansion, owner ->
        val blocked = if (!expansion.readColumnValid) ReadMarkBlock.COLUMN_UNAVAILABLE else batch.writeBlock()
        when {
            blocked != null -> notice = BatchNotice.ReadMarkRejected(blocked, backend)
            batch.markRead(expansion.books.map { it.key }.toSet(), action == ReadMarkAction.MARK_READ, owner) -> {
                try { batch.wake() } catch (failure: CancellationException) { throw failure } catch (_: Exception) { }
                finishSelection()
                refreshPending()
            }
            else -> notice = BatchNotice.ReadMarkRejected(null)
        }
    }

    /** Rereads [pendingReads]; a book that newly failed is notified once. */
    private fun refreshPending() {
        val generation = ++pendingGeneration
        viewModelScope.launch {
            dismissing?.join()
            val read = try {
                val owner = selection()?.token
                val latest = batch.pendingReads()
                if (selection()?.token != owner) null else owner to latest
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // A local read failure keeps the shown marks; the next change rereads them.
                null
            }
            if (read == null || generation != pendingGeneration) return@launch
            val (owner, latest) = read
            // A target ends only with its sync, whose import the shown page may not have yet: reread both.
            val ended = pendingOwner == owner && pendingReads.any { (book, shown) -> shown is PendingRead.Pending && book !in latest }
            if (ended && visible) reload() else applyPending(owner, latest)
        }
    }

    /** Shows [latest] for the selection [owner]; a book that newly failed is notified once. */
    private fun applyPending(owner: UUID?, latest: Map<BookKey, PendingRead>) {
        val previous = pendingReads.takeIf { pendingOwner == owner }
        pendingOwner = owner
        pendingReads = latest
        if (previous == null) return
        val failed = latest.filter { (book, read) -> read is PendingRead.Failed && previous[book] !is PendingRead.Failed }
        if (failed.isNotEmpty()) {
            readFailure = ReadFailureNotice(failed.size, failed.values.firstNotNullOfOrNull { (it as PendingRead.Failed).error })
        }
    }

    /** Leaving the page ends the failure marks it showed (R13); a retry or a new mark shows them again. */
    private fun dismissShownFailures() {
        val shown = pendingReads.filterValues { it is PendingRead.Failed }.keys
        if (shown.isEmpty()) return
        pendingGeneration++
        pendingReads = pendingReads - shown
        dismissing = viewModelScope.launch {
            try {
                batch.dismissReadFailures(shown)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // They show again on return; the next successful sync dismisses them too.
            }
        }
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
            val choice = try {
                expansion?.let { ReadMarkChoice.of(it, batch.writeBlock(), pendingTargets(batch.pendingReads())) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
            if (selected != set) return@launch
            selectedBooks = expansion?.books?.size
            readMark = choice
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
                keepAwake { load(generation) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // A local read failure keeps the previous page; the next refresh retries.
            }
        }
    }

    private suspend fun load(generation: Long) {
        restoring.join()
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
            // Read before the page: a target that ended with its sync is then always replaced by that sync's import.
            dismissing?.join()
            val pending = try {
                batch.pendingReads()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
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
            if (pending != null) {
                pendingGeneration++
                applyPending(selected.token, pending)
            }
            if (coverToken != selected.token) resetCovers(selected.token)
            // A selection belongs to one library; an import or copy change only recounts it.
            if (selectionOwner != null && selectionOwner != selected.token) finishSelection() else expandSelection()
            val key = listOf(selected.token, current.revision, request.search, request.searchScope, request.filters, categorization, folder,
                request.sort, request.foldersAscending, request.offset)
            if (key != pageKey) {
                pageKey = key
                requested.clear()
                // The batch of the page left behind is replaced by this page's (R10).
                cancelCoverWaits()
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
        viewModelScope.launch { keepAwake {
            try {
                val missing = mutableListOf<BookKey>()
                for (row in rows.distinctBy { it.key }) {
                    if (row.key in coverImages) continue
                    val cached = covers.read(row.key)
                    if (generation != loadGeneration) return@keepAwake
                    if (cached != null) coverImages[row.key] = cached
                    else if (row.hasCover && row.key !in requested) missing.add(row.key)
                }
                if (missing.isEmpty()) return@keepAwake
                val task = covers.request(missing, token) ?: return@keepAwake
                requested.addAll(missing)
                // Followed even if a newer load of the same page started meanwhile: it does not submit again.
                if (task !in waiting) waiting[task] = viewModelScope.launch {
                    covers.changes(task).collect { keepAwake { showPublished(token) } }
                }
                covers.wake()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // The title placeholders stay.
            }
        } }
    }

    /** Reads the shown page's covers that have no image yet; a batch publishes them one at a time. */
    private suspend fun showPublished(token: UUID) {
        for (key in pageBooks) {
            if (key in coverImages || coverToken != token) continue
            covers.read(key)?.let { if (key in pageBooks && coverToken == token) coverImages[key] = it }
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
                    QueueLibraryBatch(dependencies.copyService, dependencies.taskCoordinator, dependencies.maintenance, dependencies.state,
                        dependencies.metadata, dependencies.librarySources, dependencies.taskQueue, dependencies.readStatusService),
                    dependencies.state::formatPriority,
                    keepAwake = CpuAwake(context.applicationContext, "library-page", counted = true)::during,
                    syncState = { token -> dependencies.taskQueue.latestLibrarySync(token)?.state?.takeUnless { it is TaskState.Finished } },
                    viewStore = SettingsLibraryViewStore(dependencies.state),
                ))!!
            }
        }
    }
}

private fun TaskRecord.isLibrarySync() =
    (submission.request as? TaskRequest.CandidateConfiguration)?.operation == TaskRequest.CandidateConfiguration.LIBRARY_SYNC

/** Targets that count for the mark choice (R26): failed writes keep the import's state. */
private fun pendingTargets(reads: Map<BookKey, PendingRead>) =
    reads.filterValues { it is PendingRead.Pending }.mapValues { it.value.target }

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
