package org.example.project.admin.features.terminal.presentation

import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalFilter
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalSort

/**
 * What the Terminals filter panel edits on the card layout (content narrower than the 960dp table
 * breakpoint), held as a draft until its apply button: the status filter and the sort. Search stays in
 * the bar at every width, so it is not part of the panel and never cleared by its reset or by
 * clear-all. On a non-compact window the status is a quick chip in the bar instead
 * ([activeCount]'s `statusInPanel`). The table layout sorts by its headers and needs no panel.
 */
data class TerminalPanelFilter(
    val status: TerminalFilter,
    val sort: TerminalSort,
    val sortAscending: Boolean,
) {
    val isDefaultSort: Boolean get() = sort == DEFAULT_SORT && sortAscending

    /** The Filter button's badge: the status where the panel holds it, and a non-default sort. */
    fun activeCount(statusInPanel: Boolean): Int = (if (statusInPanel && status != TerminalFilter.ALL) 1 else 0) + (if (isDefaultSort) 0 else 1)

    /** The panel's reset and the bar's clear-all: every status, the default sort. */
    fun cleared(): TerminalPanelFilter = NONE

    /**
     * The `onSort` calls that turn [applied]'s sort into this one. `TerminalsViewModel.onSort` flips
     * the direction of the current key and starts another key ascending, so a descending other key
     * takes two calls.
     */
    fun sortTogglesFrom(applied: TerminalPanelFilter): List<TerminalSort> =
        when {
            sort != applied.sort -> if (sortAscending) listOf(sort) else listOf(sort, sort)
            sortAscending != applied.sortAscending -> listOf(sort)
            else -> emptyList()
        }

    /** Hands this draft to the view model, sending only what differs from [applied]. */
    fun applyTo(
        applied: TerminalPanelFilter,
        onFilter: (TerminalFilter) -> Unit,
        onSort: (TerminalSort) -> Unit,
    ) {
        if (status != applied.status) onFilter(status)
        sortTogglesFrom(applied).forEach(onSort)
    }

    /**
     * Whether the panel shows the status: on a phone ([statusInPanel]), or once this draft changed it,
     * so a resize that moves the status back into the bar cannot hide an edit apply would send.
     */
    fun showsStatus(
        statusInPanel: Boolean,
        applied: TerminalPanelFilter,
    ): Boolean = statusInPanel || status != applied.status

    companion object {
        /** The sort [TerminalsState.Data] starts with (ascending); any other counts as a filter. */
        val DEFAULT_SORT = TerminalSort.TERMINAL_ID

        val NONE = TerminalPanelFilter(status = TerminalFilter.ALL, sort = DEFAULT_SORT, sortAscending = true)

        fun of(data: TerminalsState.Data): TerminalPanelFilter = TerminalPanelFilter(data.filter, data.sort, data.sortAscending)
    }
}
