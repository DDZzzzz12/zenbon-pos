package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Horizontal cell padding (4.dp each side) a column costs on top of its [PosTableColumn.minWidth]. */
private val CellPadding: Dp = 8.dp

/**
 * The width a table needs so that no column is squeezed below its [PosTableColumn.minWidth].
 *
 * Columns share the row by [PosTableColumn.weight], so column `i` receives
 * `width * weight_i / totalWeight`. It gets at least `minWidth_i + 8.dp` when
 * `width >= (minWidth_i + 8.dp) * totalWeight / weight_i`; the table needs the largest such value
 * over all columns, and never less than the plain sum of `minWidth + 8.dp`. With equal weights and
 * equal minimums this is exactly that sum; otherwise the column whose minimum is large relative to
 * its weight dominates. `0.dp` for no columns.
 * A column whose weight is not positive only contributes to the plain sum.
 */
fun requiredTableWidth(columns: List<PosTableColumn>): Dp {
    val sum = columns.fold(0.dp) { acc, col -> acc + col.minWidth + CellPadding }
    val totalWeight = columns.fold(0f) { acc, col -> acc + col.weight }
    val weighted =
        columns.maxOfOrNull { col ->
            if (col.weight <= 0f) 0.dp else ((col.minWidth + CellPadding).value * totalWeight / col.weight).dp
        } ?: 0.dp
    return maxOf(sum, weighted)
}
