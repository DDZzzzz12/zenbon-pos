package org.example.project.admin.core.presentation.components

/**
 * A list screen's phone sort for [ListToggleFilterBar] / [ListTogglePanel]: a picker over [options]
 * plus an ascending/descending row in the panel, applied by its button. Wider windows sort by the
 * table headers and ignore it.
 *
 * @param options the sortable columns in picker order: the screen's column key to its localized label.
 * @param applied the sort as the view model last loaded it.
 * @param default the sort the screen starts with; anything else counts in the Filter badge.
 * @param onSort applies a new sort (the view model's setter, which reloads).
 */
data class ListSortPanel(
    val options: Map<Int, String>,
    val applied: ListSortSelection,
    val default: ListSortSelection,
    val onSort: (ListSortSelection) -> Unit,
)
