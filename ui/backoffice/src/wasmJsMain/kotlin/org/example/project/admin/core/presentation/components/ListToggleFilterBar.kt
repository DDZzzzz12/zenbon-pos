package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterDraft
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterPicker
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.PosFilterSwitchRow
import org.example.project.ui.components.filter.closeWhenEmpty
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * The filter header of an [AdminListScaffold] list whose filters are a search plus one server-side
 * yes/no toggle (Zenbon filter pattern): search + Filter button on a phone, with the toggle in the
 * panel ([ListTogglePanel]); wider, search with the toggle as a quick chip below it and no panel (it
 * would be empty). Always ends with the result line; clear-all switches the toggle off and never
 * touches the search text.
 *
 * Goes in the scaffold's `toolbar` slot, with the scaffold's `filters` and `resultCount` left empty:
 * the bar draws both.
 *
 * @param toggleLabel the toggle's localized label, the same on the chip and in the panel.
 * @param applied the toggle as the view model last loaded it.
 * @param onToggle applies a new toggle value (the view model's setter, which reloads).
 * @param sort the phone sort, when the screen has one; it counts in the badge and clear-all resets it.
 */
@Composable
fun ListToggleFilterBar(
    search: PosFilterSearch,
    resultText: String,
    toggleLabel: String,
    applied: Boolean,
    onToggle: (Boolean) -> Unit,
    filterPanel: PosFilterPanelState<ListTogglePanelFilter>,
    sort: ListSortPanel? = null,
) {
    val compact = LocalPosLayout.current.isCompact
    PosFilterBar(
        search = search,
        resultText = resultText,
        quickFilters = listOf(PosFilterChipItem(label = toggleLabel, selected = applied, onClick = { onToggle(!applied) })),
        activeFilterCount = ListTogglePanelFilter(applied, sort?.applied).activeCount(sort?.default),
        // On a phone the chip (and the sort) move into the panel; wider the chip is in the bar and the
        // table headers sort, so there is no panel.
        onOpenPanel = if (compact) filterPanel::open else null,
        onClearAll = {
            onToggle(false)
            sort?.takeIf { it.applied != it.default }?.let { it.onSort(it.default) }
        },
        // The scaffold already pads its content.
        horizontalPadding = 0.dp,
    )
}

/**
 * The panel of [ListToggleFilterBar], editing [filterPanel]'s draft: only its apply button reaches
 * [onToggle], closing it drops the draft. Compose it once per screen, outside the scaffold's layout
 * branches, so a resize keeps it open with its draft.
 */
@Composable
fun ListTogglePanel(
    toggleLabel: String,
    applied: Boolean,
    onToggle: (Boolean) -> Unit,
    filterPanel: PosFilterPanelState<ListTogglePanelFilter>,
    sort: ListSortPanel? = null,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val appliedFilter = ListTogglePanelFilter(applied, sort?.applied)
    val draft = filterPanel.draft
    // Widened past the point where the fields are back in the bar or the headers, with nothing
    // drafted: an empty panel would only offer an apply button that does nothing.
    if (filterPanel.closeWhenEmpty(!draft.value.isEmptyPanel(compact, appliedFilter))) return
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = {
            filterPanel.applyDraft { target ->
                target.toApply(appliedFilter)?.let(onToggle)
                target.sortToApply(appliedFilter)?.let { sort?.onSort?.invoke(it) }
            }
        },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { it.cleared(sort?.default) },
                applyReset = {
                    onToggle(false)
                    sort?.takeIf { it.applied != it.default }?.let { it.onSort(it.default) }
                },
            )
        },
    ) {
        if (draft.value.showsToggle(compact, appliedFilter)) {
            PosFilterSwitchRow(
                label = toggleLabel,
                checked = draft.value.on,
                onCheckedChange = { draft.value = draft.value.copy(on = it) },
            )
        }
        if (sort != null && draft.value.showsSort(compact, appliedFilter)) ListSortFields(sort, draft)
    }
}

/** The sort key picker with the direction row right under it, as in the Terminals panel. */
@Composable
private fun ListSortFields(
    sort: ListSortPanel,
    draft: PosFilterDraft<ListTogglePanelFilter>,
) {
    val current = draft.value.sort ?: return
    Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
        PosFilterPicker(label = str(PosPatternKeys.sortLabel), value = sort.options[current.key].orEmpty()) { close ->
            sort.options.forEach { (key, label) ->
                PosDropdownItem(
                    text = label,
                    selected = key == current.key,
                    minHeight = adminTouchSize(40.dp),
                    onClick = {
                        close()
                        // Changing the key keeps the direction.
                        draft.value = draft.value.copy(sort = current.copy(key = key))
                    },
                )
            }
        }
        PosSegmentedButtonRow(
            options = listOf(true, false),
            selectedOption = current.ascending,
            onOptionSelected = { draft.value = draft.value.copy(sort = current.copy(ascending = it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = adminTouchSize(40.dp)),
            optionLabel = { if (it) str(PosPatternKeys.sortAscending) else str(PosPatternKeys.sortDescending) },
        )
    }
}
