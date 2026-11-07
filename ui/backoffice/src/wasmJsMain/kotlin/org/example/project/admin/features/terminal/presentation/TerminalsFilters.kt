package org.example.project.admin.features.terminal.presentation

import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalFilter
import org.example.project.admin.features.terminal.presentation.components.TerminalFilterPanelFields
import org.example.project.admin.features.terminal.presentation.components.terminalSortLabel
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.components.filter.posSortDirectionLabel
import org.example.project.ui.theme.LocalPosLayout

/**
 * The compact layout's Zenbon filter bar: search applies as typed; status and sort are panel filters
 * (the badge counts them; the status strip above has no "Inaktiv" tile on a phone, so the panel
 * carries every status), the non-default sort a removable chip on wider windows. Neither touches the
 * open detail's edit, so none of them goes through the leave guard (as before).
 */
@Composable
internal fun TerminalsFilterBar(
    data: TerminalsState.Data,
    viewModel: TerminalsViewModel,
    filterPanel: PosFilterPanelState<TerminalPanelFilter>,
) {
    val statusInPanel = true
    val applied = TerminalPanelFilter.of(data)
    PosFilterBar(
        search = PosFilterSearch(data.search, viewModel::onSearch, str(AdminStringKeys.TerminalsSearchPlaceholder)),
        // Every terminal is loaded at once, so the count is exact (never "N+").
        resultText = filterResultLine(data.visible.size, AdminStringKeys.TerminalsResultCountOne, AdminStringKeys.TerminalsResultCount),
        appliedFilters = sortAppliedChips(applied, viewModel),
        activeFilterCount = applied.activeCount(statusInPanel),
        onOpenPanel = filterPanel::open,
        labelledPhoneFilterButton = true,
        // Status and sort; never the search text, which sits in the bar (PosFilterBar contract).
        onClearAll = { applied.cleared().applyTo(applied, viewModel::onFilter, viewModel::onSort) },
    )
}

/** The applied non-default sort as one removable chip, described from the APPLIED state. */
@Composable
private fun sortAppliedChips(
    applied: TerminalPanelFilter,
    viewModel: TerminalsViewModel,
): List<PosFilterChipItem> {
    if (applied.isDefaultSort) return emptyList()
    val label = str(PosPatternKeys.sortChip, terminalSortLabel(applied.sort), posSortDirectionLabel(applied.sortAscending))
    val resetSort = applied.copy(sort = TerminalPanelFilter.DEFAULT_SORT, sortAscending = true)
    return listOf(PosFilterChipItem(label = label, onClick = { resetSort.applyTo(applied, viewModel::onFilter, viewModel::onSort) }))
}

/** The panel editing [filterPanel]'s draft; only its apply button reaches the view model. */
@Composable
internal fun TerminalsFilterPanel(
    data: TerminalsState.Data,
    viewModel: TerminalsViewModel,
    filterPanel: PosFilterPanelState<TerminalPanelFilter>,
) {
    if (!filterPanel.isOpen) return
    val compact = LocalPosLayout.current.isCompact
    val applied = TerminalPanelFilter.of(data)
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = { filterPanel.applyDraft { draft -> draft.applyTo(applied, viewModel::onFilter, viewModel::onSort) } },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { it.cleared() },
                applyReset = { applied.cleared().applyTo(applied, viewModel::onFilter, viewModel::onSort) },
            )
        },
    ) {
        TerminalFilterPanelFields(
            draft = filterPanel.draft,
            showStatus = filterPanel.draft.value.showsStatus(statusInPanel = true, applied = applied),
        )
    }
}

/**
 * Terminals exist but none matches the search and status: says so, with a reset of the status when
 * one narrows the list (search is cleared in its own field).
 */
@Composable
internal fun TerminalsNoMatches(
    data: TerminalsState.Data,
    viewModel: TerminalsViewModel,
) {
    val resettable = data.filter != TerminalFilter.ALL
    AdminListEmptyState(
        message = str(PosPatternKeys.filterNoResults),
        actionText = if (resettable) str(AdminStringKeys.CommonResetFilters) else null,
        onAction = if (resettable) ({ viewModel.onFilter(TerminalFilter.ALL) }) else null,
    )
}
