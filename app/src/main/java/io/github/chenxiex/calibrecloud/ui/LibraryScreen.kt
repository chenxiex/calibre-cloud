package io.github.chenxiex.calibrecloud.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.library.BookRow
import io.github.chenxiex.calibrecloud.library.BookSortKey
import io.github.chenxiex.calibrecloud.library.Categorization
import io.github.chenxiex.calibrecloud.library.DownloadFilter
import io.github.chenxiex.calibrecloud.library.FolderRow
import io.github.chenxiex.calibrecloud.library.LibraryFilters
import io.github.chenxiex.calibrecloud.library.LibraryProblem
import io.github.chenxiex.calibrecloud.library.ReadFilter
import io.github.chenxiex.calibrecloud.library.ReadMarkAction
import io.github.chenxiex.calibrecloud.library.ReadMarkBlock
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskState

// Count first (R27): the most cells of at least these sizes, then stretched to fill the content box.
private val MIN_CELL_WIDTH = 96.dp
private val CELL_INSET = 4.dp
private val MIN_LIST_ROW_HEIGHT = 72.dp

/**
 * Top bar and the paged content of the library, or of the search page while one is open. Nothing
 * here scrolls, animates or waits on the network: the shown page is the view model's last local
 * query result. The view menu and the filter panel take the content area and exclude each other.
 */
@Composable
internal fun LibraryScreen(
    model: LibraryViewModel,
    onOpen: (CopyKey, String) -> Unit = { _, _ -> },
    onNoFormat: (BookKey, String) -> Unit = { _, _ -> },
    mark: OpenMark? = null,
    onCancelDownload: () -> Unit = {},
    downloads: Map<BookKey, DownloadingMark> = emptyMap(),
    onCancelTask: (TaskId) -> Unit = {},
    openMore: (Int) -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var menuPage by rememberSaveable { mutableIntStateOf(0) }
    var columnsOpen by rememberSaveable { mutableStateOf(false) }
    var columnsPage by rememberSaveable { mutableIntStateOf(0) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var filterPage by rememberSaveable { mutableIntStateOf(0) }
    var historyPage by rememberSaveable { mutableIntStateOf(0) }
    var batchOpen by rememberSaveable { mutableStateOf(false) }
    val selecting = model.selected != null
    LaunchedEffect(selecting) { if (!selecting) batchOpen = false }
    DisposableEffect(model) {
        model.setVisible(true)
        onDispose { model.setVisible(false) }
    }
    val closePanels = {
        menuOpen = false
        columnsOpen = false
        filterOpen = false
    }
    BackHandler(enabled = selecting || menuOpen || filterOpen || model.search != null || model.folder != null) {
        when {
            model.removal != null -> model.cancelRemoval()
            batchOpen -> batchOpen = false
            selecting -> model.finishSelection()
            columnsOpen -> columnsOpen = false
            menuOpen -> menuOpen = false
            filterOpen -> filterOpen = false
            model.search != null -> model.closeSearch()
            else -> model.closeFolder()
        }
    }
    val toggleMenu = {
        menuOpen = !menuOpen
        columnsOpen = false
        filterOpen = false
        menuPage = 0
    }
    val toggleFilter = {
        filterOpen = !filterOpen
        menuOpen = false
        columnsOpen = false
        filterPage = 0
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val search = model.search
            if (selecting) {
                SelectionTopBar(model, batchOpen) { batchOpen = !batchOpen }
            } else if (search != null) {
                SearchTopBar(model, menuOpen, filterOpen, onBack = { closePanels(); model.closeSearch() },
                    onClosePanels = closePanels, onFilter = toggleFilter, onMenu = toggleMenu)
            } else {
                LibraryTopBar(model, menuOpen, filterOpen, onSearch = {
                    closePanels()
                    historyPage = 0
                    model.openSearch()
                }, onFilter = toggleFilter, onMenu = toggleMenu)
            }
            HorizontalRule()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    menuOpen -> ViewMenu(model, menuPage, { menuPage = it }) {
                        columnsOpen = true
                        columnsPage = 0
                    }
                    filterOpen -> FilterPanel(model, filterPage) { filterPage = it }
                    search != null && search.query == null && model.overview != null ->
                        SearchHome(model, historyPage, { historyPage = it }, onSubmitted = closePanels)
                    else -> LibraryBody(model, BookMarks(mark, downloads, onCancelDownload, onCancelTask, !selecting), openMore,
                        onSelect = { closePanels(); model.toggleItem(it) }) { row ->
                        val format = row.defaultFormat?.format
                        if (format == null) onNoFormat(row.key, row.title) else onOpen(CopyKey(row.key, format), row.title)
                    }
                }
            }
        }
        if (menuOpen && columnsOpen) {
            ColumnPicker(model, columnsPage, { columnsPage = it }) { columnsOpen = false }
        }
        if (selecting && batchOpen) BatchMenu(model) { batchOpen = false }
        model.removal?.let { RemovalDialog(model, it) }
    }
}

@Composable
private fun LibraryTopBar(
    model: LibraryViewModel, menuOpen: Boolean, filterOpen: Boolean, onSearch: () -> Unit, onFilter: () -> Unit, onMenu: () -> Unit,
) {
    val folder = model.folder
    TopBar(
        Modifier.testTag("library_top_bar"),
        navigation = folder?.let { { BackAction(stringResource(R.string.library_back), Modifier.testTag("library_back")) { model.closeFolder() } } },
        actions = {
            // Searching and filtering need a complete import.
            val ready = model.overview != null
            IconAction(R.drawable.ic_search, stringResource(R.string.library_search), ready, Modifier.testTag("library_search_button"),
                iconSize = SEARCH_ICON_SIZE, onClick = onSearch)
            FilterButton(model, ready, filterOpen, onFilter)
            ViewButton(model, menuOpen, onMenu)
        },
    ) {
        TopBarTitle(
            when {
                folder != null -> folder.name ?: stringResource(R.string.library_unnamed_folder)
                else -> categoryTitle(model)
            },
            Modifier.testTag("library_title"),
        )
    }
}

@Composable
internal fun ViewButton(model: LibraryViewModel, menuOpen: Boolean, onMenu: () -> Unit) {
    IconAction(
        if (model.viewMode == LibraryViewMode.GRID) R.drawable.ic_view_grid else R.drawable.ic_view_list,
        stringResource(R.string.library_view), true, Modifier.testTag("library_view_button"), active = menuOpen, onClick = onMenu,
    )
}

private val FILTER_DOT = 8.dp

/** Applied filters add a dot to the icon and to its description, so the state reads without colour. */
@Composable
internal fun FilterButton(model: LibraryViewModel, enabled: Boolean, open: Boolean, onFilter: () -> Unit) {
    val applied = model.filters != LibraryFilters()
    Box {
        IconAction(R.drawable.ic_filter, stringResource(if (applied) R.string.library_filter_active else R.string.library_filter), enabled,
            Modifier.testTag("library_filter_button"), active = open, onClick = onFilter)
        if (applied) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = SECTION_GAP, end = SECTION_GAP).size(FILTER_DOT)
                .background(if (open) PAPER else INK, MARK_SHAPE).border(BORDER, INK, MARK_SHAPE))
        }
    }
}

@Composable
private fun categoryTitle(model: LibraryViewModel): String = when (val value = model.categorization) {
    Categorization.None -> stringResource(R.string.library_title)
    Categorization.Series -> stringResource(R.string.library_category_series)
    Categorization.Tags -> stringResource(R.string.library_category_tags)
    is Categorization.Column -> columnName(model, value) ?: stringResource(R.string.library_title)
}

private fun columnName(model: LibraryViewModel, value: Categorization.Column) =
    model.overview?.categoryColumns?.firstOrNull { it.id == value.id }?.name

@Composable
private fun LibraryBody(
    model: LibraryViewModel, marks: BookMarks, openMore: (Int) -> Unit, onSelect: (LibraryItem) -> Unit, openBook: (BookRow) -> Unit,
) {
    val content = model.content
    val shown = (content as? LibraryContent.Books)?.let { it.offset to it.total }
        ?: (content as? LibraryContent.Folders)?.let { it.offset to it.total }
    val capacity = model.capacity.coerceAtLeast(1)
    PagedArea(
        page = shown?.let { it.first / capacity } ?: 0,
        pages = shown?.let { pageCount(it.second, capacity) } ?: 1,
        tagPrefix = "library", onPage = model::showPage,
        modifier = Modifier.fillMaxSize(), showBar = shown != null,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = TIGHT_GAP, end = TIGHT_GAP, top = TIGHT_GAP).testTag("library_content")) {
            val geometry = when (model.viewMode) {
                LibraryViewMode.GRID -> gridGeometry(maxWidth.value, maxHeight.value, MIN_CELL_WIDTH.value, CELL_INSET.value)
                LibraryViewMode.LIST -> listGeometry(maxWidth.value, maxHeight.value, MIN_LIST_ROW_HEIGHT.value)
            }
            LaunchedEffect(geometry.capacity) { model.onMeasured(geometry.capacity) }
            when (content) {
                LibraryContent.Loading -> Message(stringResource(R.string.library_loading))
                LibraryContent.Unconfigured -> Message(stringResource(R.string.library_unconfigured)) {
                    ActionButton(stringResource(R.string.library_open_settings), true, Modifier.testTag("library_open_settings"), ButtonKind.PRIMARY) {
                        openMore(MoreTarget.LOCATION)
                    }
                }
                // A sync under way (such as the first one of a new library) is shown instead of offering another one.
                LibraryContent.NoMetadata -> Message(stringResource(when (model.syncing) {
                    null -> R.string.library_no_metadata
                    TaskState.Queued, is TaskState.Running -> R.string.library_syncing
                    else -> R.string.library_sync_waiting
                })) {
                    if (model.syncing == null) {
                        ActionButton(stringResource(R.string.library_sync), true, Modifier.testTag("library_sync"), ButtonKind.PRIMARY) {
                            openMore(MoreTarget.SYNC)
                        }
                    } else {
                        ActionButton(stringResource(R.string.library_sync_progress), true, Modifier.testTag("library_sync_progress"), ButtonKind.PRIMARY) {
                            openMore(MoreTarget.SYNC)
                        }
                    }
                    ActionButton(stringResource(R.string.library_downloaded_files), true, Modifier.testTag("library_downloaded_files")) {
                        openMore(MoreTarget.DOWNLOAD_LIST)
                    }
                }
                is LibraryContent.Problem -> Message(stringResource(when (content.problem) {
                    LibraryProblem.CATEGORY_COLUMN_INVALID -> R.string.library_problem_category_invalid
                    LibraryProblem.READ_FILTER_UNAVAILABLE -> R.string.library_problem_read_filter
                    LibraryProblem.SEARCH_COLUMN_INVALID -> R.string.library_problem_search_column
                    LibraryProblem.NO_METADATA -> R.string.library_no_metadata
                }))
                is LibraryContent.Books -> if (content.total == 0) {
                    Message(stringResource(if (model.search == null && model.filters == LibraryFilters())
                        R.string.library_empty else R.string.library_empty_filtered))
                } else {
                    ItemPage(model, geometry, maxWidth, maxHeight, content.rows.map { LibraryItem.Book(it) }, marks, onSelect, openBook)
                }
                is LibraryContent.Folders -> if (content.total == 0) {
                    Message(stringResource(R.string.library_empty))
                } else {
                    ItemPage(model, geometry, maxWidth, maxHeight, content.rows.map { LibraryItem.Folder(it) }, marks, onSelect, openBook)
                }
            }
        }
    }
}

/**
 * What replaces a book's download check: the open in progress, otherwise any unfinished download of
 * the book; and how a tap on it cancels that download. In selection mode ([cancellable] false) the
 * mark only shows the state and a tap selects the book.
 */
private class BookMarks(
    val mark: OpenMark?,
    val downloads: Map<BookKey, DownloadingMark>,
    val onCancelOpen: () -> Unit,
    val onCancelTask: (TaskId) -> Unit,
    val cancellable: Boolean,
) {
    fun of(row: BookRow) = mark?.takeIf { it.book == row.key }
        ?: downloads[row.key]?.let { OpenMark(row.key, it.fraction, warning = false, cancellable = true) }

    /** Null in selection mode, where the mark does not take taps. */
    fun cancelOf(row: BookRow): (() -> Unit)? = if (!cancellable) null else {
        { if (mark?.book == row.key) onCancelOpen() else downloads[row.key]?.let { onCancelTask(it.task) } }
    }
}

internal sealed interface LibraryItem {
    data class Book(val row: BookRow) : LibraryItem
    data class Folder(val row: FolderRow) : LibraryItem
}

private fun LibraryViewModel.toggleItem(item: LibraryItem) = when (item) {
    is LibraryItem.Book -> toggleBook(item.row.key)
    is LibraryItem.Folder -> toggleFolder(item.row.key)
}

private fun LibraryViewModel.isSelected(item: LibraryItem): Boolean {
    val set = selected ?: return false
    return when (item) {
        is LibraryItem.Book -> item.row.key in set.books
        is LibraryItem.Folder -> item.row.key in set.folders
    }
}

@Composable
private fun Message(text: String, actions: @Composable ColumnScope.() -> Unit = {}) =
    EmptyMessage(text, Modifier.testTag("library_message"), actions)

/** Cells keep the page geometry's size; the slack beside them is spread evenly, so a short last page stays aligned. */
@Composable
private fun ItemPage(
    model: LibraryViewModel, geometry: PageGeometry, width: Dp, height: Dp, items: List<LibraryItem>, marks: BookMarks,
    onSelect: (LibraryItem) -> Unit, openBook: (BookRow) -> Unit,
) {
    val shown = items.take(geometry.capacity)
    // A long press starts selection; while selecting, a tap toggles instead of opening.
    val open = Activation(onSelect) { item: LibraryItem ->
        when {
            model.selected != null -> onSelect(item)
            item is LibraryItem.Folder -> model.openFolder(item.row.key)
            item is LibraryItem.Book -> openBook(item.row)
        }
    }
    if (model.viewMode == LibraryViewMode.GRID) {
        val columnGap = ((width.value - geometry.cellWidth * geometry.columns) / (geometry.columns + 1)).coerceAtLeast(0f).dp
        val rowGap = ((height.value - geometry.cellHeight * geometry.rows) / (geometry.rows + 1)).coerceAtLeast(0f).dp
        Column(Modifier.fillMaxSize().padding(top = rowGap), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            shown.chunked(geometry.columns).forEach { line ->
                Row(Modifier.fillMaxWidth().padding(start = columnGap), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
                    line.forEach { item ->
                        Box(Modifier.size(geometry.cellWidth.dp, geometry.cellHeight.dp).padding(CELL_INSET)) {
                            GridCell(model, item, marks, open)
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            shown.forEach { item -> ListRow(model, item, geometry.cellHeight.dp, marks, open) }
        }
    }
}

private class Activation(val longPress: (LibraryItem) -> Unit, val tap: (LibraryItem) -> Unit)

@Composable
private fun Modifier.activation(item: LibraryItem, activation: Activation, selected: Boolean?): Modifier {
    val description = selected?.let { stringResource(if (it) R.string.selection_chosen else R.string.selection_not_chosen) }
    return combinedClickable(
        interactionSource = null, indication = null,
        onLongClick = { activation.longPress(item) },
    ) { activation.tap(item) }.semantics {
        if (selected != null) {
            this.selected = selected
            stateDescription = requireNotNull(description)
        }
    }
}

private fun tagOf(item: LibraryItem) = when (item) {
    is LibraryItem.Book -> "book_${item.row.key.sourceId}"
    is LibraryItem.Folder -> "folder_${item.row.key.name ?: ""}"
}

private fun representative(item: LibraryItem) = when (item) {
    is LibraryItem.Book -> item.row
    is LibraryItem.Folder -> item.row.representative
}

@Composable
private fun GridCell(model: LibraryViewModel, item: LibraryItem, marks: BookMarks, open: Activation) {
    val row = representative(item)
    val cover = model.coverImages[row.key]
    val selected = model.selected?.let { model.isSelected(item) }
    Box(Modifier.fillMaxSize().testTag(tagOf(item)).activation(item, open, selected)) {
        // A chosen item is framed thickly as well as ticked, so the state survives greyscale.
        CoverBox(cover, if (item is LibraryItem.Book) row.title else "",
            Modifier.fillMaxSize().then(if (selected == true) Modifier.border(FRAME, INK) else Modifier))
        when (item) {
            is LibraryItem.Book -> {
                if (item.row.read == true) ReadRibbon(Modifier.align(Alignment.TopEnd))
                val open = marks.of(item.row)
                when {
                    // The touch area reaches the cell corner; the drawn ring keeps the check's inset.
                    open != null -> OpenMarkIcon(item.row, open, marks.cancelOf(item.row), Modifier.align(Alignment.BottomEnd))
                    item.row.downloaded -> DownloadMark(Modifier.align(Alignment.BottomEnd).padding(MARK_INSET))
                }
                if (item.row.defaultFormat?.sourceMissing == true) {
                    SourceMissing(Modifier.align(Alignment.BottomStart).padding(MARK_INSET))
                }
            }
            // Without a cover the name and count fill the placeholder; with one they form the bottom label.
            is LibraryItem.Folder -> FolderLabel(item.row, cover == null, Modifier.align(if (cover == null) Alignment.Center else Alignment.BottomStart))
        }
        // Top left stays free: the read ribbon is top right and the download mark bottom right.
        if (selected != null) SelectionBox(selected, Modifier.align(Alignment.TopStart).padding(MARK_INSET), onImage = true)
    }
}

@Composable
private fun FolderLabel(folder: FolderRow, placeholder: Boolean, modifier: Modifier) {
    val name = folder.key.name ?: stringResource(R.string.library_unnamed_folder)
    val count = pluralStringResource(R.plurals.library_folder_books, folder.bookCount, folder.bookCount)
    val description = stringResource(R.string.library_folder_description, name, count)
    val frame = if (placeholder) modifier.fillMaxWidth() else modifier.fillMaxWidth().background(PAPER).border(BORDER, INK)
    Column(
        frame.padding(horizontal = TIGHT_GAP, vertical = TIGHT_GAP).semantics { contentDescription = description },
        horizontalAlignment = if (placeholder) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = if (placeholder) 4 else 1,
            overflow = TextOverflow.Ellipsis, textAlign = if (placeholder) TextAlign.Center else TextAlign.Start)
        Text(count, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun ListRow(model: LibraryViewModel, item: LibraryItem, height: Dp, marks: BookMarks, open: Activation) {
    val row = representative(item)
    val selected = model.selected?.let { model.isSelected(item) }
    // The rule stays inside the measured row height, so a page of rows still fits.
    Column(Modifier.fillMaxWidth().height(height).testTag(tagOf(item)).activation(item, open, selected)) {
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = SECTION_GAP, vertical = TIGHT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                SelectionBox(selected, Modifier)
                Spacer(Modifier.width(LEADING_GAP))
            }
            CoverBox(model.coverImages[row.key], if (item is LibraryItem.Book) row.title else "",
                Modifier.fillMaxHeight().aspectRatio(1f / COVER_ASPECT))
            Spacer(Modifier.width(LEADING_GAP))
            // A short row gives the title fewer lines; the author and size lines always stay.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                when (item) {
                    is LibraryItem.Book -> {
                        Text(row.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        // Unknown authors and sizes leave no placeholder text at all.
                        if (row.authors.isNotEmpty()) SupportingText(row.authors.joinToString(stringResource(R.string.list_separator)))
                        ListMeta(row)
                    }
                    is LibraryItem.Folder -> {
                        Text(item.row.key.name ?: stringResource(R.string.library_unnamed_folder), Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        SupportingText(pluralStringResource(R.plurals.library_folder_books, item.row.bookCount, item.row.bookCount))
                    }
                }
            }
            val open = (item as? LibraryItem.Book)?.let { marks.of(it.row) }
            if (item is LibraryItem.Book && (open != null || item.row.downloaded)) {
                Spacer(Modifier.width(TIGHT_GAP))
                if (open != null) OpenMarkIcon(item.row, open, marks.cancelOf(item.row), Modifier)
                else DownloadMark(Modifier.padding(MARK_INSET))
            }
        }
        // Aligned with the cover and the marks rather than the page margin.
        HorizontalRule(Modifier.padding(horizontal = SECTION_GAP))
    }
}

@Composable
private fun ListMeta(row: BookRow) {
    val size = row.defaultFormat?.sizeBytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
    val missing = row.defaultFormat?.sourceMissing == true
    if (row.read != true && size == null && !missing) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row.read == true) {
            TagLabel(stringResource(R.string.library_read_label))
            Spacer(Modifier.width(SECTION_GAP))
        }
        if (missing) {
            SourceMissing(Modifier)
            Spacer(Modifier.width(SECTION_GAP))
        }
        if (size != null) SupportingText(size)
    }
}

@Composable
private fun CoverBox(bitmap: Bitmap?, title: String, modifier: Modifier) {
    Box(modifier.border(BORDER, INK).background(PAPER), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text(title, Modifier.padding(TIGHT_GAP), style = MaterialTheme.typography.labelSmall, maxLines = 6, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
        } else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

private val RIBBON_OUTER = 50.dp
private val RIBBON_INNER = 28.dp
private val RIBBON_TEXT = 11.sp

/**
 * Trapezoid band across the top-right corner: its legs lie on the cover's top and right edges, its
 * parallel sides run diagonally. The text keeps the state readable without colour.
 */
@Composable
private fun ReadRibbon(modifier: Modifier) {
    // The band's centre line meets both edges at the mean of the inner and outer distances.
    val shift = (RIBBON_OUTER - (RIBBON_OUTER + RIBBON_INNER) / 2) / 2
    Box(modifier.size(RIBBON_OUTER).testTag("read_ribbon")) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.width
            val inner = RIBBON_INNER.toPx()
            drawPath(Path().apply {
                moveTo(0f, 0f)
                lineTo(outer - inner, 0f)
                lineTo(outer, inner)
                lineTo(outer, outer)
                close()
            }, INK)
        }
        Text(
            stringResource(R.string.library_read_label),
            Modifier.align(Alignment.Center).offset(x = shift, y = -shift).graphicsLayer { rotationZ = 45f },
            color = PAPER, fontSize = RIBBON_TEXT, lineHeight = RIBBON_TEXT, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

private val MARK_SIZE = 24.dp
private val MARK_ICON = 16.dp
private val MARK_INSET = 4.dp
/** The progress arc; the cross disc inside it is smaller by this on each side, so both span [MARK_SIZE]. */
private val RING_WIDTH = 3.dp
private val CROSS_ICON = 12.dp

/** White check in a black disc; a thin white rim keeps the disc apart from a dark cover. */
@Composable
private fun DownloadMark(modifier: Modifier) {
    val description = stringResource(R.string.library_downloaded_description)
    Box(modifier.size(MARK_SIZE).background(INK, MARK_SHAPE).border(BORDER, PAPER, MARK_SHAPE)
        .semantics { contentDescription = description }.testTag("download_mark"), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_check), null, Modifier.size(MARK_ICON), tint = PAPER)
    }
}

/**
 * A downloading or opening book, in the check's place: a white cross on a black disc while it
 * downloads, an exclamation mark when an open needs the user (its reason is in the notification). A
 * download's disc is smaller than the check's and a static progress arc hugs it from the top,
 * clockwise, so disc and arc together are exactly the check's size; there is no track or outline,
 * and an unknown share shows no arc. Tapping it cancels the download task. A failure is the check's
 * full disc with no arc and no action of its own, so a tap reaches the book and opens it again.
 */
@Composable
private fun OpenMarkIcon(row: BookRow, mark: OpenMark, onCancel: (() -> Unit)?, modifier: Modifier) {
    val description = stringResource(when {
        !mark.cancellable -> R.string.open_warning
        mark.warning -> R.string.open_warning_cancel
        else -> R.string.open_cancel_download
    }, row.title)
    val percent = mark.fraction?.let { (it * 100).toInt() }
    val progress = percent?.let { stringResource(R.string.open_download_progress, it) }
    val area = modifier.size(MARK_SIZE + MARK_INSET * 2)
    val icon = if (mark.warning) R.drawable.ic_priority_high else R.drawable.ic_close
    Box(
        if (!mark.cancellable) area.testTag("open_warning_${row.key.sourceId}").semantics { contentDescription = description }
        else area.testTag("download_progress_${row.key.sourceId}")
            .then(if (onCancel == null) Modifier else Modifier.tap(role = null, onClick = onCancel))
            .semantics {
                contentDescription = description
                if (onCancel != null) role = Role.Button
                if (progress != null) stateDescription = progress
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!mark.cancellable) {
            Box(Modifier.size(MARK_SIZE).background(INK, MARK_SHAPE).border(BORDER, PAPER, MARK_SHAPE), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(MARK_ICON), tint = PAPER)
            }
        } else {
            val fraction = mark.fraction
            Canvas(Modifier.size(MARK_SIZE)) {
                val stroke = RING_WIDTH.toPx()
                // White behind the arc keeps it visible on a dark cover, as the check's rim does.
                drawCircle(PAPER)
                drawCircle(INK, radius = size.minDimension / 2 - stroke)
                if (fraction != null) drawArc(INK, -90f, 360f * fraction, false, Offset(stroke / 2, stroke / 2),
                    Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            }
            Icon(painterResource(icon), null, Modifier.size(CROSS_ICON), tint = PAPER)
        }
    }
}

@Composable
private fun SourceMissing(modifier: Modifier) {
    TagLabel(stringResource(R.string.library_source_missing), modifier)
}

/**
 * The view menu replaces the search slot and content and pages like any other long list. Choosing an
 * option applies it and keeps the menu open; the view button or system back closes it.
 */
@Composable
private fun ViewMenu(model: LibraryViewModel, page: Int, onPage: (Int) -> Unit, openColumns: () -> Unit) {
    val entries = buildList {
        add(MenuEntry.Choice("menu_view_grid", stringResource(R.string.library_view_grid), R.drawable.ic_view_grid,
            model.viewMode == LibraryViewMode.GRID) { model.showAs(LibraryViewMode.GRID) })
        add(MenuEntry.Choice("menu_view_list", stringResource(R.string.library_view_list), R.drawable.ic_view_list,
            model.viewMode == LibraryViewMode.LIST) { model.showAs(LibraryViewMode.LIST) })
        if (model.search == null && model.folder == null) {
            add(MenuEntry.Rule)
            add(MenuEntry.Heading(stringResource(R.string.library_menu_category)))
            add(MenuEntry.Choice("menu_category_none", stringResource(R.string.library_category_none),
                selected = model.categorization == Categorization.None) { model.categorize(Categorization.None) })
            add(MenuEntry.Choice("menu_category_series", stringResource(R.string.library_category_series),
                selected = model.categorization == Categorization.Series) { model.categorize(Categorization.Series) })
            add(MenuEntry.Choice("menu_category_tags", stringResource(R.string.library_category_tags),
                selected = model.categorization == Categorization.Tags) { model.categorize(Categorization.Tags) })
            val column = model.categorization as? Categorization.Column
            val name = column?.let { columnName(model, it) }
            add(MenuEntry.Choice(
                "menu_category_more",
                if (name != null) stringResource(R.string.library_category_more_chosen, name) else stringResource(R.string.library_category_more),
                selected = column != null,
                enabled = model.overview?.categoryColumns.orEmpty().isNotEmpty(), action = openColumns,
            ))
        }
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.library_menu_sort)))
        val ascending = stringResource(R.string.library_sort_ascending)
        val descending = stringResource(R.string.library_sort_descending)
        val arrow = { up: Boolean -> if (up) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down }
        if (model.showsFolders) {
            // Folders only sort by name, so the one key is always chosen and only its direction changes.
            add(MenuEntry.Choice("menu_sort_folders", stringResource(R.string.library_sort_name), selected = true,
                trailing = arrow(model.foldersAscending), trailingDescription = if (model.foldersAscending) ascending else descending,
            ) { model.reverseFolders() })
        } else {
            val seriesFolder = model.search == null && model.categorization == Categorization.Series && model.folder?.name != null
            val keys = listOfNotNull(BookSortKey.SERIES_INDEX.takeIf { seriesFolder }, BookSortKey.TITLE, BookSortKey.ADDED, BookSortKey.RATING)
            keys.forEach { key ->
                val sort = model.sort.takeIf { it.key == key }
                add(MenuEntry.Choice(
                    "menu_sort_${key.name.lowercase()}", stringResource(sortLabel(key)),
                    selected = sort != null, trailing = sort?.let { arrow(it.ascending) },
                    trailingDescription = sort?.let { if (it.ascending) ascending else descending },
                ) { model.sortBy(key) })
            }
        }
    }
    PagedEntries(entries, page, onPage, Modifier.testTag("library_menu"), "menu")
}

/**
 * Filters of the shown page in three groups. Download and read state are single choices: picking a
 * value replaces the other and picking it again clears it; several formats are alternatives. Choosing
 * keeps the panel open; the filter button or system back closes it. The read group is unavailable
 * without a valid read column, but a value chosen earlier can still be removed.
 */
@Composable
private fun FilterPanel(model: LibraryViewModel, page: Int, onPage: (Int) -> Unit) {
    val filters = model.filters
    val overview = model.overview
    val readable = overview?.readFilterAvailable == true
    val entries = buildList {
        add(MenuEntry.Heading(stringResource(R.string.filter_downloads)))
        listOf(DownloadFilter.DOWNLOADED to R.string.filter_downloaded, DownloadFilter.NOT_DOWNLOADED to R.string.filter_not_downloaded)
            .forEach { (value, label) ->
                add(MenuEntry.Choice("filter_download_${value.name.lowercase()}", stringResource(label),
                    selected = value == filters.download) {
                    model.updateFilters { it.copy(download = if (it.download == value) null else value) }
                })
            }
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.filter_reads)))
        if (!readable) add(MenuEntry.Note("filter_read_unavailable", stringResource(R.string.filter_read_unavailable)))
        listOf(ReadFilter.READ to R.string.filter_read, ReadFilter.UNREAD to R.string.filter_unread).forEach { (value, label) ->
            add(MenuEntry.Choice("filter_read_${value.name.lowercase()}", stringResource(label),
                selected = value == filters.read, enabled = readable || value == filters.read) {
                model.updateFilters { it.copy(read = if (it.read == value) null else value) }
            })
        }
        // Formats chosen earlier stay listed so they can be removed after a sync drops them.
        val formats = (overview?.formats.orEmpty() + filters.formats).distinct().sortedBy { it.value }
        if (formats.isNotEmpty()) {
            add(MenuEntry.Rule)
            add(MenuEntry.Heading(stringResource(R.string.filter_formats)))
            formats.forEach { format ->
                add(MenuEntry.Choice("filter_format_${format.value}", format.value, selected = format in filters.formats,
                    multiple = true) {
                    model.updateFilters { it.copy(formats = it.formats.toggle(format)) }
                })
            }
        }
    }
    PagedEntries(entries, page, onPage, Modifier.testTag("library_filter_panel"), "filter")
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/**
 * The supported custom columns, shown over the menu without animation. Choosing one categorizes by it
 * and returns to the still open view menu; a tap outside the panel or system back also returns.
 */
@Composable
private fun ColumnPicker(model: LibraryViewModel, page: Int, onPage: (Int) -> Unit, close: () -> Unit) {
    val entries = model.overview?.categoryColumns.orEmpty().map { column ->
        val value = Categorization.Column(column.id)
        MenuEntry.Choice("menu_category_column_${column.id.sourceId}", column.name, selected = model.categorization == value) {
            model.categorize(value)
            close()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The panel fits its rows and pages only when they exceed most of the screen.
        val natural = BAR_HEIGHT + BORDER + ROW_HEIGHT * entries.size.coerceAtLeast(1) + FRAME * 2
        OverlayPanel(close, Modifier.fillMaxWidth(0.86f).height(minOf(natural, maxHeight * 0.8f)).testTag("category_columns")) {
            TopBar(actions = {
                IconAction(R.drawable.ic_close, stringResource(R.string.library_category_columns_close), true,
                    Modifier.testTag("category_columns_close"), onClick = close)
            }) {
                TopBarTitle(stringResource(R.string.library_category_columns_title))
            }
            HorizontalRule()
            Box(Modifier.weight(1f).fillMaxWidth()) { PagedEntries(entries, page, onPage, Modifier, "category_columns") }
        }
    }
}

private fun sortLabel(key: BookSortKey): Int = when (key) {
    BookSortKey.TITLE -> R.string.library_sort_title
    BookSortKey.ADDED -> R.string.library_sort_added
    BookSortKey.RATING -> R.string.library_sort_rating
    BookSortKey.SERIES_INDEX -> R.string.library_sort_series
}

/** Each item's check box in selection mode; tagged so tests and device checks can read the state. */
@Composable
private fun SelectionBox(selected: Boolean, modifier: Modifier, onImage: Boolean = false) {
    SelectionMark(selected, multiple = true, modifier.testTag(if (selected) "selection_box_checked" else "selection_box"), onImage = onImage)
}

/**
 * Selection mode replaces the title or folder back with "完成" and offers only download and more, so
 * search, filters and view cannot change the level the selection belongs to. The count is the
 * expanded, deduplicated number of books.
 */
@Composable
private fun SelectionTopBar(model: LibraryViewModel, moreOpen: Boolean, onMore: () -> Unit) {
    val count = model.selectedBooks
    val idle = !model.batchBusy
    TopBar(
        Modifier.testTag("selection_top_bar"),
        navigation = {
            ActionButton(stringResource(R.string.selection_done), true, Modifier.testTag("selection_done"), ButtonKind.TEXT) {
                model.finishSelection()
            }
        },
        actions = {
            IconAction(R.drawable.ic_download, stringResource(R.string.selection_download), idle && count != 0,
                Modifier.testTag("selection_download")) { model.downloadSelection() }
            IconAction(R.drawable.ic_more_vert, stringResource(R.string.selection_more), idle,
                Modifier.testTag("selection_more"), active = moreOpen, onClick = onMore)
        },
    ) {
        Text(
            if (count == null) stringResource(R.string.selection_counting) else pluralStringResource(R.plurals.selection_books, count, count),
            Modifier.weight(1f).testTag("selection_count"), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The selection's "more" menu, a small popup under the top bar's right end over the still visible page:
 * the one read-state mark R26 offers (disabled with its reason while it cannot be submitted) and removing
 * downloads, which asks for confirmation first. A tap outside or system back closes it.
 */
@Composable
private fun BatchMenu(model: LibraryViewModel, close: () -> Unit) {
    val choice = model.readMark
    val entries = buildList {
        val reason = when (choice?.blocked) {
            ReadMarkBlock.NO_BOOKS -> R.string.selection_mark_no_books
            ReadMarkBlock.COLUMN_UNAVAILABLE -> R.string.selection_mark_no_column
            ReadMarkBlock.WRITE_UNAVAILABLE -> R.string.selection_mark_no_write
            null -> null
        }
        if (reason != null) add(MenuEntry.Note("selection_mark_reason", stringResource(reason)))
        val unread = choice?.action == ReadMarkAction.MARK_UNREAD
        add(MenuEntry.Choice(if (unread) "selection_mark_unread" else "selection_mark_read",
            stringResource(if (unread) R.string.selection_mark_unread else R.string.selection_mark_read),
            enabled = choice != null && choice.blocked == null) {
            // Source write-back arrives in phase 4; until then the choice is never enabled.
        })
        add(MenuEntry.Rule)
        add(MenuEntry.Choice("selection_remove", stringResource(R.string.selection_remove), enabled = !model.batchBusy) {
            close()
            model.prepareRemoval()
        })
    }
    OverlayPanel(close, Modifier.padding(top = BAR_HEIGHT, end = TIGHT_GAP).width(BATCH_MENU_WIDTH).testTag("selection_menu"), Alignment.TopEnd) {
        entries.forEach { MenuRow(it) }
    }
}

private val BATCH_MENU_WIDTH = 240.dp

/** Confirmation over the page: the frozen books, formats, copies and bytes; nothing is removed until confirmed. */
@Composable
private fun RemovalDialog(model: LibraryViewModel, removal: RemovalConfirmation) {
    val context = LocalContext.current
    ConfirmPanel("removal_dialog", !model.batchBusy, model::cancelRemoval, model::confirmRemoval, stringResource(R.string.removal_title),
        cancelTag = "removal_cancel", confirmTag = "removal_confirm") {
        Text(pluralStringResource(R.plurals.removal_books, removal.books, removal.books), Modifier.testTag("removal_books"))
        val formats = removal.formats
        Text(
            if (formats == null) stringResource(R.string.removal_all_formats)
            else stringResource(R.string.removal_formats, formats.map { it.value }.sorted().joinToString(stringResource(R.string.removal_format_separator))),
            Modifier.testTag("removal_formats"),
        )
        val copies = removal.plan.copies.size
        Text(
            if (copies == 0) stringResource(R.string.removal_no_copies)
            else pluralStringResource(R.plurals.removal_copies, copies, copies, Formatter.formatShortFileSize(context, removal.plan.bytes)),
            Modifier.testTag("removal_copies"),
        )
    }
}
