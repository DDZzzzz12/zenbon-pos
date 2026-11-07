package org.example.project.ui.components

import androidx.compose.ui.unit.Dp

/**
 * The indices of the columns the **table** form shows in a pane [width] wide: every column whose
 * [PosAdaptiveColumn.hideInTableBelow] the pane reaches, in declaration order (NEW-06). The card form
 * ignores this and shows them all.
 */
fun visibleTableColumns(
    columns: List<PosAdaptiveColumn>,
    width: Dp,
): List<Int> = columns.indices.filter { width >= columns[it].hideInTableBelow }

/** True when a pane [width] wide shows [columns] as a table rather than cards. */
fun showsAsTable(
    columns: List<PosAdaptiveColumn>,
    width: Dp,
    cardBelow: Dp,
): Boolean {
    val visible = visibleTableColumns(columns, width)
    return visible.isNotEmpty() && width >= maxOf(cardBelow, requiredTableWidth(visible.map { columns[it].column }))
}
