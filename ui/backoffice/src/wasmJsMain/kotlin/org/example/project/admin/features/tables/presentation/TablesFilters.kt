package org.example.project.admin.features.tables.presentation

import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.tables.presentation.components.TableFilterPanelFields
import org.example.project.admin.features.tables.presentation.components.TableLocationOption
import org.example.project.admin.features.tables.presentation.components.TableStatusLegend
import org.example.project.admin.features.tables.presentation.components.tableSortColumnLabel
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.state.TableManagementState
import org.example.project.features.settings.presentation.state.getFilteredTables
import org.example.project.features.settings.presentation.state.getLocationCounts
import org.example.project.ui.components.filter.PosFilterBar
import org.example.project.ui.components.filter.PosFilterChipItem
import org.example.project.ui.components.filter.PosFilterPanel
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.PosFilterSearch
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.components.filter.posSortDirectionLabel
import org.example.project.ui.guard.LocalLeaveGuard

/** The locations that have tables, in the order the former chips listed them, with their counts. */
internal fun TableManagementState.locationOptions(): List<TableLocationOption> {
    val names = tables.associate { it.locationTypeId to it.locationTypeName }
    return getLocationCounts()
        .filter { (_, count) -> count > 0 }
        .map { (id, count) -> TableLocationOption(id = id, name = names[id] ?: id.toString(), count = count) }
}

/**
 * The card layout's Zenbon filter bar: search applies as typed; the location is a quick chip on a
 * non-compact window and a panel filter on a phone; the sort is a panel filter (the badge counts
 * them), its non-default value a removable chip on wider windows. Every filter change that can
 * replace rows with unsaved edits goes through the leave guard, exactly as before.
 */
@Composable
internal fun TablesFilterBar(
    state: TableManagementState,
    viewModel: TableViewModel,
    filterPanel: PosFilterPanelState<TablePanelFilter>,
    showPanel: Boolean,
) {
    val guard = LocalLeaveGuard.current
    // The panel (location + sort) is the phone's; a wider bar keeps the location as chips and sorts by header.
    val locationInPanel = showPanel
    val applied = TablePanelFilter.of(state)
    PosFilterBar(
        search =
            PosFilterSearch(
                query = state.searchQuery,
                onQueryChange = { query -> guard.requestLeave { viewModel.updateSearchQuery(query) } },
                placeholder = str(SettingsKeys.searchTables),
            ),
        // Every table is loaded at once, so the count is exact (never "N+").
        resultText = filterResultLine(state.getFilteredTables().size, AdminStringKeys.TablesResultCountOne, AdminStringKeys.TablesResultCount),
        resultContent = { TableStatusLegend(state.tables.statusCounts()) },
        quickFilters = if (locationInPanel) emptyList() else locationQuickChips(state, viewModel),
        appliedFilters = if (showPanel) sortAppliedChips(applied, viewModel) else emptyList(),
        activeFilterCount = if (showPanel) applied.activeCount(locationInPanel) else 0,
        onOpenPanel = if (showPanel) filterPanel::open else null,
        // Location and sort; never the search text, which sits in the bar (PosFilterBar contract).
        // Nothing narrows the list while "Alle" is the pick: no clear-all then (the "Alle" chip would
        // otherwise count as an applied filter).
        onClearAll =
            if (state.selectedLocationFilter == null && applied.isDefaultSort) {
                null
            } else {
                { guard.requestLeave { applied.cleared().applyTo(applied, viewModel::selectLocationFilter, viewModel::setSort) } }
            },
    )
}

/** One quick chip per location with tables; tapping the selected one returns to every location. */
@Composable
private fun locationQuickChips(
    state: TableManagementState,
    viewModel: TableViewModel,
): List<PosFilterChipItem> {
    val guard = LocalLeaveGuard.current
    val selected = state.selectedLocationFilter
    val all =
        PosFilterChipItem(
            label = "${str(CommonKeys.all)} ${state.tables.size}",
            selected = selected == null,
            onClick = { guard.requestLeave { viewModel.selectLocationFilter(null) } },
        )
    return listOf(all) +
        state.locationOptions().map { location ->
            PosFilterChipItem(
                label = "${location.name} ${location.count}",
                selected = selected == location.id,
                onClick = { guard.requestLeave { viewModel.selectLocationFilter(location.id) } },
            )
        }
}

/** The applied non-default sort as one removable chip, described from the APPLIED state. */
@Composable
private fun sortAppliedChips(
    applied: TablePanelFilter,
    viewModel: TableViewModel,
): List<PosFilterChipItem> {
    if (applied.isDefaultSort) return emptyList()
    val guard = LocalLeaveGuard.current
    val label =
        str(
            PosPatternKeys.sortChip,
            tableSortColumnLabel(applied.sortColumn),
            posSortDirectionLabel(applied.sortAscending),
        )
    val resetSort = applied.copy(sortColumn = TablePanelFilter.DEFAULT_SORT_COLUMN, sortAscending = true)
    return listOf(
        PosFilterChipItem(
            label = label,
            onClick = { guard.requestLeave { resetSort.applyTo(applied, viewModel::selectLocationFilter, viewModel::setSort) } },
        ),
    )
}

/**
 * The panel editing [filterPanel]'s draft ([compact]: the phone layout, where it holds the location too); only its apply button reaches the view model, through the
 * leave guard when it changes anything (the panel stays open while the guard asks, so "keep editing"
 * keeps the draft too).
 */
@Composable
internal fun TablesFilterPanel(
    state: TableManagementState,
    viewModel: TableViewModel,
    filterPanel: PosFilterPanelState<TablePanelFilter>,
    compact: Boolean,
) {
    if (!filterPanel.isOpen) return
    val guard = LocalLeaveGuard.current
    val applied = TablePanelFilter.of(state)
    PosFilterPanel(
        onDismiss = filterPanel::close,
        onApply = {
            if (filterPanel.draft.value == applied) {
                filterPanel.close()
            } else {
                guard.requestLeave {
                    filterPanel.applyDraft { draft -> draft.applyTo(applied, viewModel::selectLocationFilter, viewModel::setSort) }
                }
            }
        },
        onReset = {
            filterPanel.reset(
                compact = compact,
                clearDraft = { it.cleared() },
                applyReset = { guard.requestLeave { applied.cleared().applyTo(applied, viewModel::selectLocationFilter, viewModel::setSort) } },
            )
        },
    ) {
        TableFilterPanelFields(
            draft = filterPanel.draft,
            locations = state.locationOptions(),
            totalCount = state.tables.size,
            showLocation = filterPanel.draft.value.showsLocation(locationInPanel = compact, applied = applied),
        )
    }
}

/**
 * No cards to show: no tables at all, or nothing matches the filters, with a reset of the panel
 * filters when a location narrows the list (search is cleared in its own field).
 */
@Composable
internal fun TablesEmptyState(
    state: TableManagementState,
    viewModel: TableViewModel,
) {
    if (state.tables.isEmpty()) {
        AdminListEmptyState(message = str(SettingsKeys.noTablesFound))
        return
    }
    val guard = LocalLeaveGuard.current
    val applied = TablePanelFilter.of(state)
    val resettable = state.selectedLocationFilter != null
    AdminListEmptyState(
        message = str(PosPatternKeys.filterNoResults),
        actionText = if (resettable) str(AdminStringKeys.CommonResetFilters) else null,
        onAction =
            if (resettable) {
                { guard.requestLeave { applied.cleared().applyTo(applied, viewModel::selectLocationFilter, viewModel::setSort) } }
            } else {
                null
            },
    )
}
