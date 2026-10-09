package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.library.SearchScope

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
 * Back, the search input and the filter and view buttons. The input is focused on entry; only the
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
    TopBar(
        Modifier.testTag("search_top_bar"),
        navigation = { BackAction(stringResource(R.string.search_back), Modifier.testTag("search_back"), onBack) },
        actions = {
            FilterButton(model, true, filterOpen, onFilter)
            ViewButton(model, menuOpen, onMenu)
        },
    ) {
        SearchField(
            model.searchInput, model::editSearch, stringResource(R.string.search_input), focus, "search_input",
            stringResource(R.string.search_clear), "search_clear",
            onClear = {
                onClosePanels()
                model.editSearch("")
                focus.requestFocus()
                keyboard?.show()
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        ) {
            if (scope != SearchScope.All) {
                choices.firstOrNull { it.scope == scope }?.let {
                    Text(stringResource(R.string.search_scope_prefix, it.label), Modifier.testTag("search_scope_label"),
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
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
    Column(Modifier.fillMaxSize().testTag("search_home")) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN).testTag("search_scopes"),
            horizontalArrangement = Arrangement.spacedBy(SECTION_GAP),
        ) {
            scopeChoices(model).forEach { choice ->
                ChoiceChip(choice.label, choice.scope == scope, Modifier.testTag("search_scope_${choice.tag}")) { model.chooseScope(choice.scope) }
            }
        }
        HorizontalRule()
        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(start = PAGE_MARGIN), verticalAlignment = Alignment.CenterVertically) {
            Heading(stringResource(R.string.search_history), Modifier.weight(1f))
            ActionButton(stringResource(R.string.search_history_clear), model.history.isNotEmpty(), Modifier.testTag("search_history_clear"),
                ButtonKind.TEXT, stringResource(R.string.search_history_clear_description), model::clearHistory)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model.history.isEmpty()) {
                EmptyMessage(stringResource(R.string.search_history_empty), Modifier.testTag("search_history_empty"))
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
