package org.example.project.admin.features.tables.presentation

import org.example.project.features.settings.presentation.state.TableManagementState
import org.example.project.features.settings.presentation.state.TableSortColumn

/**
 * What the Tische filter panel edits on the card layout (content narrower than 600dp), held as a
 * draft until its apply button: the location and the sort. Search stays in the bar at every width,
 * so it is not part of the panel and never cleared by its reset or by clear-all. On a non-compact
 * window the location is a quick chip in the bar instead ([activeCount]'s `locationInPanel`).
 */
data class TablePanelFilter(
    val locationTypeId: Long?,
    val sortColumn: TableSortColumn,
    val sortAscending: Boolean,
) {
    val isDefaultSort: Boolean get() = sortColumn == DEFAULT_SORT_COLUMN && sortAscending

    /** The Filter button's badge: the location where the panel holds it, and a non-default sort. */
    fun activeCount(locationInPanel: Boolean): Int = (if (locationInPanel && locationTypeId != null) 1 else 0) + (if (isDefaultSort) 0 else 1)

    /** The panel's reset and the bar's clear-all: every location, the default sort. */
    fun cleared(): TablePanelFilter = NONE

    /**
     * The `setSort` calls that turn [applied]'s sort into this one. `TableViewModel.setSort` flips the
     * direction of the current column and starts another column ascending, so a descending other
     * column takes two calls.
     */
    fun sortTogglesFrom(applied: TablePanelFilter): List<TableSortColumn> =
        when {
            sortColumn != applied.sortColumn -> if (sortAscending) listOf(sortColumn) else listOf(sortColumn, sortColumn)
            sortAscending != applied.sortAscending -> listOf(sortColumn)
            else -> emptyList()
        }

    /** Hands this draft to the view model, sending only what differs from [applied]. */
    fun applyTo(
        applied: TablePanelFilter,
        onLocation: (Long?) -> Unit,
        onSortToggle: (TableSortColumn) -> Unit,
    ) {
        if (locationTypeId != applied.locationTypeId) onLocation(locationTypeId)
        sortTogglesFrom(applied).forEach(onSortToggle)
    }

    /**
     * Whether the panel shows the location: on a phone ([locationInPanel]), or once this draft changed
     * it, so a resize that moves the location back into the bar cannot hide an edit apply would send.
     */
    fun showsLocation(
        locationInPanel: Boolean,
        applied: TablePanelFilter,
    ): Boolean = locationInPanel || locationTypeId != applied.locationTypeId

    companion object {
        /** The sort [TableManagementState] starts with (ascending); any other counts as a filter. */
        val DEFAULT_SORT_COLUMN = TableSortColumn.TABLE_NUMBER

        val NONE = TablePanelFilter(locationTypeId = null, sortColumn = DEFAULT_SORT_COLUMN, sortAscending = true)

        fun of(state: TableManagementState): TablePanelFilter = TablePanelFilter(state.selectedLocationFilter, state.sortColumn, state.sortAscending)
    }
}
