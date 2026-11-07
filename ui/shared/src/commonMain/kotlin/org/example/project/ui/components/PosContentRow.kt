package org.example.project.ui.components

import androidx.compose.runtime.Composable

/**
 * One row of a [PosContentTable]. Each cell is a composable, which is what [PosTableRow] cannot do:
 * a money cell must go through [PosMoneyText] and a status cell through [PosStatusChip], and neither
 * of those is a `String`.
 *
 * [cells] should have one entry per column; a ragged row simply leaves the surplus columns empty
 * rather than throwing, matching [PosDataTable]'s defensive normalisation.
 */
data class PosContentRow(
    val cells: List<@Composable () -> Unit>,
    val onClick: (() -> Unit)? = null,
    /**
     * Detail rendered full-width beneath this row, or null when the row shows nothing further.
     *
     * The caller decides when it is present, so expansion state lives with the caller's data rather
     * than inside the table — a table that remembered which row was open would keep the wrong row
     * open after the list changed underneath it.
     *
     * It is laid out **in flow**, so it must not introduce its own vertical scroll: on wasm a
     * scrollable inside a scrollable measures to infinite height and crashes the composition.
     */
    val expanded: (@Composable () -> Unit)? = null,
    /**
     * Cells with nothing to say for this row: a [PosCardRole.Meta] cell listed here is left out of the
     * card's meta line, label included, so an empty value never shows a bare caption. Table form is
     * unaffected (the column is still there, the cell just empty).
     */
    val blankCells: Set<Int> = emptySet(),
)
