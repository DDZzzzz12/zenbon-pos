package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/** Pure column maths for [PosFormGrid]: decided by the width the grid has, never the window. */
object PosFormGridLayout {
    val MIN_CELL: Dp = 240.dp
    val GAP: Dp = 12.dp
    const val MAX_COLUMNS = 3

    fun columnsFor(
        width: Dp,
        minCell: Dp = MIN_CELL,
        gap: Dp = GAP,
        maxColumns: Int = MAX_COLUMNS,
    ): Int {
        require(maxColumns >= 1) { "maxColumns must be at least 1, was $maxColumns" }
        if (!width.value.isFinite()) return 1
        val fit = ((width + gap) / (minCell + gap)).toInt()
        return fit.coerceIn(1, maxColumns)
    }

    /** Width of one cell; floored to whole dp so [columns] cells plus gaps always fit the line. */
    fun cellWidth(
        width: Dp,
        columns: Int,
        gap: Dp = GAP,
    ): Dp = floor(((width - gap * (columns - 1)) / columns).value).dp
}
