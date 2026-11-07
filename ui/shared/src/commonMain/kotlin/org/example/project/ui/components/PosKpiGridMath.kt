package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How many equal-width KPI cards fit per row, balanced so a 4-item set is 2+2, not 3+1. */
object PosKpiGridMath {
    val MIN_ITEM_WIDTH: Dp = 150.dp
    val GAP: Dp = 8.dp

    fun columnsFor(
        width: Dp,
        itemCount: Int,
        minItemWidth: Dp = MIN_ITEM_WIDTH,
        gap: Dp = GAP,
    ): Int {
        require(itemCount >= 0) { "itemCount must not be negative, was $itemCount" }
        if (itemCount <= 1) return 1
        val fit = ((width + gap) / (minItemWidth + gap)).toInt().coerceIn(1, itemCount)
        val rows = (itemCount + fit - 1) / fit
        return (itemCount + rows - 1) / rows
    }
}
