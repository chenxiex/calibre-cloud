package io.github.chenxiex.calibrecloud.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import io.github.chenxiex.calibrecloud.library.FolderRow
import io.github.chenxiex.calibrecloud.library.DownloadFilter
import io.github.chenxiex.calibrecloud.library.LibraryFilters
import io.github.chenxiex.calibrecloud.library.LibraryProblem
import io.github.chenxiex.calibrecloud.library.ReadFilter
import io.github.chenxiex.calibrecloud.model.BackendKind

/** The "更多" pages that the library hands over to when it needs configuration, sync or downloads. */
internal object MoreTarget {
    const val LOCAL_AUTHORIZATION = 0
    const val ONEDRIVE_TASKS = 3
    const val DOWNLOAD_LIST = 7
}

// Count first (R27): the most cells of at least these sizes, then stretched to fill the content box.
private val MIN_CELL_WIDTH = 96.dp
private val CELL_INSET = 4.dp
private val MIN_LIST_ROW_HEIGHT = 72.dp
private val PAGE_BAR_HEIGHT = 48.dp
private val MENU_PAGE_BAR_HEIGHT = 40.dp
private val MENU_CHOICE_HEIGHT = 44.dp
private val MENU_HEADING_HEIGHT = 40.dp
private val MENU_RULE_HEIGHT = 9.dp
private val MENU_NOTE_HEIGHT = 56.dp
internal val BAR_HEIGHT = 56.dp

/**
 * Top bar and the paged content of the library, or of the search page while one is open. Nothing
 * here scrolls, animates or waits on the network: the shown page is the view model's last local
 * query result. The view menu and the filter panel take the content area and exclude each other.
 */
@Composable
internal fun LibraryScreen(model: LibraryViewModel, openMore: (Int) -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var menuPage by rememberSaveable { mutableIntStateOf(0) }
    var columnsOpen by rememberSaveable { mutableStateOf(false) }
    var columnsPage by rememberSaveable { mutableIntStateOf(0) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var filterPage by rememberSaveable { mutableIntStateOf(0) }
    var historyPage by rememberSaveable { mutableIntStateOf(0) }
    DisposableEffect(model) {
        model.setVisible(true)
        onDispose { model.setVisible(false) }
    }
    val closePanels = {
        menuOpen = false
        columnsOpen = false
        filterOpen = false
    }
    BackHandler(enabled = menuOpen || filterOpen || model.search != null || model.folder != null) {
        when {
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
            if (search != null) {
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
                    else -> LibraryBody(model, openMore)
                }
            }
        }
        if (menuOpen && columnsOpen) {
            ColumnPicker(model, columnsPage, { columnsPage = it }) { columnsOpen = false }
        }
    }
}

@Composable
private fun LibraryTopBar(
    model: LibraryViewModel, menuOpen: Boolean, filterOpen: Boolean, onSearch: () -> Unit, onFilter: () -> Unit, onMenu: () -> Unit,
) {
    val folder = model.folder
    Row(
        Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 4.dp).testTag("library_top_bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folder != null) {
            IconAction(R.drawable.ic_back, stringResource(R.string.library_back), true, Modifier.testTag("library_back")) { model.closeFolder() }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            when {
                folder != null -> folder.name ?: stringResource(R.string.library_unnamed_folder)
                else -> categoryTitle(model)
            },
            Modifier.weight(1f).testTag("library_title"),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // Searching and filtering need a complete import.
        val ready = model.overview != null
        IconAction(R.drawable.ic_search, stringResource(R.string.library_search), ready, Modifier.testTag("library_search_button"),
            iconSize = 20.dp, onClick = onSearch)
        FilterButton(model, ready, filterOpen, onFilter)
        ViewButton(model, menuOpen, onMenu)
    }
}

@Composable
internal fun ViewButton(model: LibraryViewModel, menuOpen: Boolean, onMenu: () -> Unit) {
    IconAction(
        if (model.viewMode == LibraryViewMode.GRID) R.drawable.ic_view_grid else R.drawable.ic_view_list,
        stringResource(R.string.library_view), true, Modifier.testTag("library_view_button"), active = menuOpen, onClick = onMenu,
    )
}

/** Applied filters add a dot to the icon and to its description, so the state reads without colour. */
@Composable
internal fun FilterButton(model: LibraryViewModel, enabled: Boolean, open: Boolean, onFilter: () -> Unit) {
    val applied = model.filters != LibraryFilters()
    Box {
        IconAction(R.drawable.ic_filter, stringResource(if (applied) R.string.library_filter_active else R.string.library_filter), enabled,
            Modifier.testTag("library_filter_button"), active = open, onClick = onFilter)
        if (applied) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(8.dp)
                .background(if (open) Color.White else Color.Black, RoundedCornerShape(4.dp)).border(1.dp, Color.Black, RoundedCornerShape(4.dp)))
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
private fun LibraryBody(model: LibraryViewModel, openMore: (Int) -> Unit) {
    val content = model.content
    val shown = (content as? LibraryContent.Books)?.let { it.offset to it.total }
        ?: (content as? LibraryContent.Folders)?.let { it.offset to it.total }
    val capacity = model.capacity.coerceAtLeast(1)
    PagedArea(
        page = shown?.let { it.first / capacity } ?: 0,
        pages = shown?.let { pageCount(it.second, capacity) } ?: 1,
        tagPrefix = "library", barHeight = PAGE_BAR_HEIGHT, onPage = model::showPage,
        modifier = Modifier.fillMaxSize(), showBar = shown != null,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = 4.dp).testTag("library_content")) {
            val geometry = when (model.viewMode) {
                LibraryViewMode.GRID -> gridGeometry(maxWidth.value, maxHeight.value, MIN_CELL_WIDTH.value, CELL_INSET.value)
                LibraryViewMode.LIST -> listGeometry(maxWidth.value, maxHeight.value, MIN_LIST_ROW_HEIGHT.value)
            }
            LaunchedEffect(geometry.capacity) { model.onMeasured(geometry.capacity) }
            when (content) {
                LibraryContent.Loading -> Message(stringResource(R.string.library_loading))
                LibraryContent.Unconfigured -> Message(stringResource(R.string.library_unconfigured)) {
                    StaticButton(stringResource(R.string.library_open_settings), true, Modifier.testTag("library_open_settings")) {
                        openMore(MoreTarget.LOCAL_AUTHORIZATION)
                    }
                }
                LibraryContent.NoMetadata -> Message(stringResource(R.string.library_no_metadata)) {
                    StaticButton(stringResource(R.string.library_sync), true, Modifier.testTag("library_sync")) {
                        openMore(if (model.backend == BackendKind.ONEDRIVE) MoreTarget.ONEDRIVE_TASKS else MoreTarget.LOCAL_AUTHORIZATION)
                    }
                    Spacer(Modifier.height(8.dp))
                    StaticButton(stringResource(R.string.library_downloaded_files), true, Modifier.testTag("library_downloaded_files")) {
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
                    ItemPage(model, geometry, maxWidth, maxHeight, content.rows.map { LibraryItem.Book(it) })
                }
                is LibraryContent.Folders -> if (content.total == 0) {
                    Message(stringResource(R.string.library_empty))
                } else {
                    ItemPage(model, geometry, maxWidth, maxHeight, content.rows.map { LibraryItem.Folder(it) })
                }
            }
        }
    }
}

private sealed interface LibraryItem {
    data class Book(val row: BookRow) : LibraryItem
    data class Folder(val row: FolderRow) : LibraryItem
}

@Composable
private fun Message(text: String, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, Modifier.testTag("library_message"), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        actions()
    }
}

/** Cells keep the page geometry's size; the slack beside them is spread evenly, so a short last page stays aligned. */
@Composable
private fun ItemPage(model: LibraryViewModel, geometry: PageGeometry, width: Dp, height: Dp, items: List<LibraryItem>) {
    val shown = items.take(geometry.capacity)
    val open = { item: LibraryItem -> if (item is LibraryItem.Folder) model.openFolder(item.row.key) }
    if (model.viewMode == LibraryViewMode.GRID) {
        val columnGap = ((width.value - geometry.cellWidth * geometry.columns) / (geometry.columns + 1)).coerceAtLeast(0f).dp
        val rowGap = ((height.value - geometry.cellHeight * geometry.rows) / (geometry.rows + 1)).coerceAtLeast(0f).dp
        Column(Modifier.fillMaxSize().padding(top = rowGap), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            shown.chunked(geometry.columns).forEach { line ->
                Row(Modifier.fillMaxWidth().padding(start = columnGap), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
                    line.forEach { item ->
                        Box(Modifier.size(geometry.cellWidth.dp, geometry.cellHeight.dp).padding(CELL_INSET)) {
                            GridCell(model, item, open)
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            shown.forEach { item -> ListRow(model, item, geometry.cellHeight.dp, open) }
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
private fun GridCell(model: LibraryViewModel, item: LibraryItem, open: (LibraryItem) -> Unit) {
    val row = representative(item)
    val cover = model.coverImages[row.key]
    Box(Modifier.fillMaxSize().testTag(tagOf(item)).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        enabled = item is LibraryItem.Folder,
    ) { open(item) }) {
        CoverBox(cover, if (item is LibraryItem.Book) row.title else "", Modifier.fillMaxSize())
        when (item) {
            is LibraryItem.Book -> {
                if (item.row.read == true) ReadRibbon(Modifier.align(Alignment.TopEnd))
                if (item.row.downloaded) DownloadMark(Modifier.align(Alignment.BottomEnd).padding(4.dp))
                if (item.row.defaultFormat?.sourceMissing == true) {
                    SourceMissing(Modifier.align(Alignment.BottomStart).padding(4.dp))
                }
            }
            // Without a cover the name and count fill the placeholder; with one they form the bottom label.
            is LibraryItem.Folder -> FolderLabel(item.row, cover == null, Modifier.align(if (cover == null) Alignment.Center else Alignment.BottomStart))
        }
    }
}

@Composable
private fun FolderLabel(folder: FolderRow, placeholder: Boolean, modifier: Modifier) {
    val name = folder.key.name ?: stringResource(R.string.library_unnamed_folder)
    val count = pluralStringResource(R.plurals.library_folder_books, folder.bookCount, folder.bookCount)
    val description = stringResource(R.string.library_folder_description, name, folder.bookCount)
    val frame = if (placeholder) modifier.fillMaxWidth() else modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Black)
    Column(
        frame.padding(horizontal = 6.dp, vertical = 4.dp).semantics { contentDescription = description },
        horizontalAlignment = if (placeholder) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = if (placeholder) 4 else 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (placeholder) TextAlign.Center else TextAlign.Start)
        Text(count, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ListRow(model: LibraryViewModel, item: LibraryItem, height: Dp, open: (LibraryItem) -> Unit) {
    val row = representative(item)
    Row(
        Modifier.fillMaxWidth().height(height).padding(horizontal = 8.dp, vertical = 4.dp).testTag(tagOf(item))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = item is LibraryItem.Folder) { open(item) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverBox(model.coverImages[row.key], if (item is LibraryItem.Book) row.title else "",
            Modifier.fillMaxHeight().aspectRatio(1f / COVER_ASPECT))
        Spacer(Modifier.width(12.dp))
        // A short row gives the title fewer lines; the author and size lines always stay.
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            when (item) {
                is LibraryItem.Book -> {
                    Text(row.title, Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    // Unknown authors and sizes leave no placeholder text at all.
                    if (row.authors.isNotEmpty()) Text(row.authors.joinToString("、"), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ListMeta(row)
                }
                is LibraryItem.Folder -> {
                    Text(item.row.key.name ?: stringResource(R.string.library_unnamed_folder), Modifier.weight(1f, fill = false),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text(pluralStringResource(R.plurals.library_folder_books, item.row.bookCount, item.row.bookCount), fontSize = 13.sp)
                }
            }
        }
        if (item is LibraryItem.Book && item.row.downloaded) {
            Spacer(Modifier.width(8.dp))
            DownloadMark(Modifier)
        }
    }
}

@Composable
private fun ListMeta(row: BookRow) {
    val size = row.defaultFormat?.sizeBytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
    val missing = row.defaultFormat?.sourceMissing == true
    if (row.read != true && size == null && !missing) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row.read == true) {
            Text(stringResource(R.string.library_read_label), Modifier.border(1.dp, Color.Black).padding(horizontal = 4.dp),
                fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        if (missing) {
            SourceMissing(Modifier)
            Spacer(Modifier.width(8.dp))
        }
        if (size != null) Text(size, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun CoverBox(bitmap: Bitmap?, title: String, modifier: Modifier) {
    Box(modifier.border(1.dp, Color.Black).background(Color.White), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text(title, Modifier.padding(4.dp), fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        } else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

private val RIBBON_OUTER = 50.dp
private val RIBBON_INNER = 28.dp

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
            }, Color.Black)
        }
        Text(
            stringResource(R.string.library_read_label),
            Modifier.align(Alignment.Center).offset(x = shift, y = -shift).graphicsLayer { rotationZ = 45f },
            color = Color.White, fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

@Composable
private fun DownloadMark(modifier: Modifier) {
    val description = stringResource(R.string.library_downloaded_description)
    Box(modifier.size(24.dp).background(Color.White).border(1.dp, Color.Black).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Text("✓", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SourceMissing(modifier: Modifier) {
    Text(stringResource(R.string.library_source_missing), modifier.background(Color.White).border(1.dp, Color.Black).padding(horizontal = 3.dp),
        fontSize = 11.sp, maxLines = 1)
}

internal sealed interface MenuEntry {
    val height: Dp

    data object Rule : MenuEntry {
        override val height get() = MENU_RULE_HEIGHT
    }

    class Heading(val label: String) : MenuEntry {
        override val height get() = MENU_HEADING_HEIGHT
    }

    /** Explains why the choices that follow are unavailable. */
    class Note(val tag: String, val text: String) : MenuEntry {
        override val height get() = MENU_NOTE_HEIGHT
    }

    /** [mark] is drawn at the row end: a check for a chosen option, an arrow for the chosen sort direction. */
    class Choice(
        val tag: String, val label: String, @DrawableRes val icon: Int? = null, @DrawableRes val mark: Int? = null,
        val markDescription: String? = null, val enabled: Boolean = true, val action: () -> Unit,
    ) : MenuEntry {
        override val height get() = MENU_CHOICE_HEIGHT
    }
}

/**
 * The view menu replaces the search slot and content and pages like any other long list. Choosing an
 * option applies it and keeps the menu open; the view button or system back closes it.
 */
@Composable
private fun ViewMenu(model: LibraryViewModel, page: Int, onPage: (Int) -> Unit, openColumns: () -> Unit) {
    val chosen = stringResource(R.string.library_menu_chosen)
    val check = { selected: Boolean -> if (selected) R.drawable.ic_check else null }
    val entries = buildList {
        add(MenuEntry.Choice("menu_view_grid", stringResource(R.string.library_view_grid), R.drawable.ic_view_grid,
            check(model.viewMode == LibraryViewMode.GRID), chosen) { model.showAs(LibraryViewMode.GRID) })
        add(MenuEntry.Choice("menu_view_list", stringResource(R.string.library_view_list), R.drawable.ic_view_list,
            check(model.viewMode == LibraryViewMode.LIST), chosen) { model.showAs(LibraryViewMode.LIST) })
        if (model.search == null && model.folder == null) {
            add(MenuEntry.Rule)
            add(MenuEntry.Heading(stringResource(R.string.library_menu_category)))
            add(MenuEntry.Choice("menu_category_none", stringResource(R.string.library_category_none),
                mark = check(model.categorization == Categorization.None), markDescription = chosen) { model.categorize(Categorization.None) })
            add(MenuEntry.Choice("menu_category_series", stringResource(R.string.library_category_series),
                mark = check(model.categorization == Categorization.Series), markDescription = chosen) { model.categorize(Categorization.Series) })
            add(MenuEntry.Choice("menu_category_tags", stringResource(R.string.library_category_tags),
                mark = check(model.categorization == Categorization.Tags), markDescription = chosen) { model.categorize(Categorization.Tags) })
            val column = model.categorization as? Categorization.Column
            val name = column?.let { columnName(model, it) }
            add(MenuEntry.Choice(
                "menu_category_more",
                if (name != null) stringResource(R.string.library_category_more_chosen, name) else stringResource(R.string.library_category_more),
                mark = check(column != null), markDescription = chosen,
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
            add(MenuEntry.Choice("menu_sort_folders", stringResource(R.string.library_sort_name), mark = arrow(model.foldersAscending),
                markDescription = if (model.foldersAscending) ascending else descending) { model.reverseFolders() })
        } else {
            val seriesFolder = model.search == null && model.categorization == Categorization.Series && model.folder?.name != null
            val keys = listOfNotNull(BookSortKey.SERIES_INDEX.takeIf { seriesFolder }, BookSortKey.TITLE, BookSortKey.ADDED, BookSortKey.RATING)
            keys.forEach { key ->
                val sort = model.sort.takeIf { it.key == key }
                add(MenuEntry.Choice(
                    "menu_sort_${key.name.lowercase()}", stringResource(sortLabel(key)),
                    mark = sort?.let { arrow(it.ascending) },
                    markDescription = sort?.let { if (it.ascending) ascending else descending },
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
    val chosen = stringResource(R.string.library_menu_chosen)
    val filters = model.filters
    val check = { selected: Boolean -> if (selected) R.drawable.ic_check else null }
    val overview = model.overview
    val readable = overview?.readFilterAvailable == true
    val entries = buildList {
        add(MenuEntry.Heading(stringResource(R.string.filter_downloads)))
        listOf(DownloadFilter.DOWNLOADED to R.string.filter_downloaded, DownloadFilter.NOT_DOWNLOADED to R.string.filter_not_downloaded)
            .forEach { (value, label) ->
                add(MenuEntry.Choice("filter_download_${value.name.lowercase()}", stringResource(label),
                    mark = check(value == filters.download), markDescription = chosen) {
                    model.updateFilters { it.copy(download = if (it.download == value) null else value) }
                })
            }
        add(MenuEntry.Rule)
        add(MenuEntry.Heading(stringResource(R.string.filter_reads)))
        if (!readable) add(MenuEntry.Note("filter_read_unavailable", stringResource(R.string.filter_read_unavailable)))
        listOf(ReadFilter.READ to R.string.filter_read, ReadFilter.UNREAD to R.string.filter_unread).forEach { (value, label) ->
            add(MenuEntry.Choice("filter_read_${value.name.lowercase()}", stringResource(label),
                mark = check(value == filters.read), markDescription = chosen, enabled = readable || value == filters.read) {
                model.updateFilters { it.copy(read = if (it.read == value) null else value) }
            })
        }
        // Formats chosen earlier stay listed so they can be removed after a sync drops them.
        val formats = (overview?.formats.orEmpty() + filters.formats).distinct().sortedBy { it.value }
        if (formats.isNotEmpty()) {
            add(MenuEntry.Rule)
            add(MenuEntry.Heading(stringResource(R.string.filter_formats)))
            formats.forEach { format ->
                add(MenuEntry.Choice("filter_format_${format.value}", format.value, mark = check(format in filters.formats), markDescription = chosen) {
                    model.updateFilters { it.copy(formats = it.formats.toggle(format)) }
                })
            }
        }
    }
    PagedEntries(entries, page, onPage, Modifier.testTag("library_filter_panel"), "filter")
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/** Lays out grouped rows by height; the page row only appears when the entries need more than one page. */
@Composable
internal fun PagedEntries(entries: List<MenuEntry>, page: Int, onPage: (Int) -> Unit, modifier: Modifier, tagPrefix: String) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val blocks = entries.map { PageBlock(it.height.value, keepWithNext = it is MenuEntry.Heading, separator = it is MenuEntry.Rule) }
        val pages = paginate(blocks, maxHeight.value).takeIf { it.size <= 1 }
            ?: paginate(blocks, maxHeight.value - MENU_PAGE_BAR_HEIGHT.value)
        val current = page.coerceIn(0, pages.size - 1)
        LaunchedEffect(current, page) { if (current != page) onPage(current) }
        PagedArea(current, pages.size, tagPrefix, MENU_PAGE_BAR_HEIGHT, onPage, Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                pages[current].forEach { MenuRow(entries[it]) }
            }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry) {
    when (entry) {
        MenuEntry.Rule -> Box(Modifier.fillMaxWidth().height(entry.height), contentAlignment = Alignment.Center) {
            HorizontalRule(Modifier.padding(horizontal = 8.dp))
        }
        is MenuEntry.Note -> Box(Modifier.fillMaxWidth().height(entry.height).padding(horizontal = 16.dp).testTag(entry.tag),
            contentAlignment = Alignment.CenterStart) {
            Text(entry.text, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        is MenuEntry.Heading -> Box(Modifier.fillMaxWidth().height(entry.height).padding(horizontal = 16.dp), contentAlignment = Alignment.BottomStart) {
            Text(entry.label, Modifier.padding(bottom = 4.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
        }
        is MenuEntry.Choice -> {
            val tint = if (entry.enabled) Color.Black else DISABLED_TINT
            Row(
                Modifier.fillMaxWidth().height(entry.height).testTag(entry.tag)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        enabled = entry.enabled, role = Role.Button, onClick = entry.action)
                    .semantics {
                        selected = entry.mark != null
                        entry.markDescription?.takeIf { entry.mark != null }?.let { stateDescription = it }
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.icon != null) {
                    Icon(painterResource(entry.icon), null, Modifier.size(22.dp), tint = tint)
                    Spacer(Modifier.width(12.dp))
                }
                Text(entry.label, Modifier.weight(1f), color = tint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (entry.mark != null) FontWeight.Bold else FontWeight.Normal)
                if (entry.mark != null) Icon(painterResource(entry.mark), null, Modifier.size(22.dp), tint = tint)
            }
        }
    }
}

/**
 * The supported custom columns, shown over the menu without animation. Choosing one categorizes by it
 * and returns to the still open view menu; a tap outside the panel or system back also returns.
 */
@Composable
private fun ColumnPicker(model: LibraryViewModel, page: Int, onPage: (Int) -> Unit, close: () -> Unit) {
    val chosen = stringResource(R.string.library_menu_chosen)
    val entries = model.overview?.categoryColumns.orEmpty().map { column ->
        val value = Categorization.Column(column.id)
        MenuEntry.Choice("menu_category_column_${column.id.sourceId}", column.name,
            mark = if (model.categorization == value) R.drawable.ic_check else null, markDescription = chosen) {
            model.categorize(value)
            close()
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        // The panel fits its rows and pages only when they exceed most of the screen.
        val natural = BAR_HEIGHT + 1.dp + MENU_CHOICE_HEIGHT * entries.size.coerceAtLeast(1) + 4.dp
        Column(
            Modifier.fillMaxWidth(0.86f).height(minOf(natural, maxHeight * 0.8f)).background(Color.White).border(2.dp, Color.Black)
                // Taps inside the panel must not fall through to the dismissing background.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .testTag("category_columns"),
        ) {
            Row(Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_category_columns_title), Modifier.weight(1f),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconAction(R.drawable.ic_close, stringResource(R.string.library_category_columns_close), true,
                    Modifier.testTag("category_columns_close"), onClick = close)
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
