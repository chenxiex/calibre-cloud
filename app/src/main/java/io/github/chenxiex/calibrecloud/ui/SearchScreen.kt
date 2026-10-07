package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.library.SearchScope

private val SEARCH_FIELD_HEIGHT = 40.dp

/** One field the search can be limited to; [tag] is stable for tests and device checks. */
private class ScopeChoice(val scope: SearchScope, val label: String, val tag: String)

@Composable
private fun scopeChoices(model: LibraryViewModel): List<ScopeChoice> = buildList {
    add(ScopeChoice(SearchScope.All, stringResource(R.string.search_scope_all), "all"))
    add(ScopeChoice(SearchScope.Title, stringResource(R.string.search_scope_title), "title"))
    add(ScopeChoice(SearchScope.Authors, stringResource(R.string.search_scope_authors), "authors"))
    add(ScopeChoice(SearchScope.Series, stringResource(R.string.search_scope_series), "series"))
    add(ScopeChoice(SearchScope.Tags, stringResource(R.string.search_scope_tags), "tags"))
    add(ScopeChoice(SearchScope.Comments, stringResource(R.string.search_scope_comments), "comments"))
    model.overview?.categoryColumns.orEmpty().forEach {
        add(ScopeChoice(SearchScope.Column(it.id), it.name, "column_${it.id.sourceId}"))
    }
}

/**
 * Back, the rounded input and the filter and view buttons. The input is focused on entry; only the
 * keyboard's search key runs a query. A chosen field is named inside the input before the text, and
 * a clear button follows any text: it empties the input, returns to the history and keeps typing.
 */
@Composable
internal fun SearchTopBar(
    model: LibraryViewModel, menuOpen: Boolean, filterOpen: Boolean,
    onBack: () -> Unit, onClosePanels: () -> Unit, onFilter: () -> Unit, onMenu: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (model.search?.query == null) focus.requestFocus() }
    val submit = {
        if (model.searchInput.isNotBlank()) {
            model.submitSearch(model.searchInput)
            onClosePanels()
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    val choices = scopeChoices(model)
    val scope = model.search?.scope ?: SearchScope.All
    Row(
        Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 4.dp).testTag("search_top_bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(R.drawable.ic_back, stringResource(R.string.search_back), true, Modifier.testTag("search_back"), onClick = onBack)
        Row(
            Modifier.weight(1f).height(SEARCH_FIELD_HEIGHT).border(1.dp, Color.Black, RoundedCornerShape(SEARCH_FIELD_HEIGHT / 2))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp), tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            if (scope != SearchScope.All) {
                choices.firstOrNull { it.scope == scope }?.let {
                    Text(stringResource(R.string.search_scope_prefix, it.label), Modifier.testTag("search_scope_label"),
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                }
            }
            val description = stringResource(R.string.search_input)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (model.searchInput.isEmpty()) Text(description, color = DISABLED_TINT, fontSize = 16.sp, maxLines = 1)
                BasicTextField(
                    model.searchInput, model::editSearch,
                    Modifier.fillMaxWidth().focusRequester(focus).testTag("search_input").semantics { contentDescription = description },
                    textStyle = TextStyle(fontSize = 16.sp, color = Color.Black), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
            }
            if (model.searchInput.isNotEmpty()) {
                IconAction(R.drawable.ic_close, stringResource(R.string.search_clear), true, Modifier.testTag("search_clear"),
                    size = 36.dp, iconSize = 18.dp) {
                    onClosePanels()
                    model.editSearch("")
                    focus.requestFocus()
                    keyboard?.show()
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        FilterButton(model, true, filterOpen, onFilter)
        ViewButton(model, menuOpen, onMenu)
    }
}

/**
 * Shown while no query is executed: the field badges above the paged history. Choosing a badge only
 * limits the next search; tapping a history entry runs it again.
 */
@Composable
internal fun SearchHome(model: LibraryViewModel, page: Int, onPage: (Int) -> Unit, onSubmitted: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = model.search?.scope ?: SearchScope.All
    val chosen = stringResource(R.string.library_menu_chosen)
    Column(Modifier.fillMaxSize().testTag("search_home")) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("search_scopes"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            scopeChoices(model).forEach { choice -> ScopeBadge(choice, choice.scope == scope, chosen) { model.chooseScope(choice.scope) } }
        }
        HorizontalRule()
        Row(Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_history), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
            val description = stringResource(R.string.search_history_clear_description)
            val enabled = model.history.isNotEmpty()
            Box(
                Modifier.heightIn(min = ICON_TOUCH_SIZE).testTag("search_history_clear")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        enabled = enabled, role = Role.Button, onClick = model::clearHistory)
                    .semantics { contentDescription = description }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.search_history_clear), color = if (enabled) Color.Black else DISABLED_TINT, fontSize = 16.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model.history.isEmpty()) {
                Text(stringResource(R.string.search_history_empty), Modifier.padding(16.dp).testTag("search_history_empty"), color = DISABLED_TINT)
            } else {
                val entries = model.history.mapIndexed { index, query ->
                    MenuEntry.Choice("search_history_$index", query) {
                        model.submitSearch(query)
                        onSubmitted()
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                }
                PagedEntries(entries, page, onPage, Modifier.testTag("search_history"), "history")
            }
        }
    }
}

/** The chosen badge is bold white on black, so it reads without colour. */
@Composable
private fun ScopeBadge(choice: ScopeChoice, selected: Boolean, chosen: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.heightIn(min = 36.dp).testTag("search_scope_${choice.tag}")
            .background(if (selected) Color.Black else Color.White, shape).border(1.dp, Color.Black, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (selected) stateDescription = chosen
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(choice.label, color = if (selected) Color.White else Color.Black, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
