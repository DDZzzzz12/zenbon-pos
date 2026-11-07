package org.example.project.ui.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single place that decides how many panes fit. Inputs are the width the **content** has
 * (measured with `BoxWithConstraints`), never the window: the admin sidebar already spends ~240dp,
 * so a window breakpoint would claim room the content does not have.
 */
object PaneBreakpoints {
    /** Content narrower than this is a phone: one pane at a time. */
    val PHONE_MAX: Dp = 600.dp

    /** One column is about one phone wide. */
    val COLUMN_MIN: Dp = 420.dp

    const val MAX_COLUMNS = 3

    /** Windows shorter than this scroll their header blocks with the list (landscape phone). */
    val SHORT_HEIGHT_MAX: Dp = 480.dp

    fun isShortWindow(height: Dp): Boolean = height < SHORT_HEIGHT_MAX

    /** [gutter] is the screen's own padding per side: it is added back for the phone cut-off only; the column count is decided on the content width itself. */
    fun layoutFor(
        contentWidth: Dp,
        maxColumns: Int = MAX_COLUMNS,
        gutter: Dp = 0.dp,
    ): PaneLayout {
        require(maxColumns >= 1) { "maxColumns must be at least 1, was $maxColumns" }
        if (contentWidth + gutter * 2 < PHONE_MAX) return PaneLayout.Single
        val fit = (contentWidth / COLUMN_MIN).toInt().coerceAtMost(maxColumns)
        return if (fit >= 2) PaneLayout.Columns(fit) else PaneLayout.Stacked
    }

    /**
     * Which half of a list/detail pair shows. [optedIn] is false for a caller that supplied no way
     * back from the detail: such a screen never drills, so it can never strand the user in a pane.
     * The pair drills until [splitMin] (never below [PHONE_MAX]), so the stacked band drills too.
     */
    fun drillVisibility(
        contentWidth: Dp,
        detailOpen: Boolean,
        optedIn: Boolean = true,
        splitMin: Dp = PHONE_MAX,
    ): DrillVisibility =
        when {
            !optedIn || contentWidth >= maxOf(splitMin, PHONE_MAX) -> DrillVisibility.Both
            detailOpen -> DrillVisibility.DetailOnly
            else -> DrillVisibility.ListOnly
        }

    /**
     * True when a drill screen should hide its own header and show the detail alone: the content is a
     * phone, a detail was opened **and** there is actually something selected to show. Screens use it
     * for `AdminListScaffold(showHeader = !phoneDetail)`; AdminSplitPane reaches the same verdict
     * from [drillVisibility], so header and pane always agree.
     */
    fun isPhoneDetail(
        contentWidth: Dp,
        detailOpen: Boolean,
        hasSelection: Boolean,
        splitMin: Dp = PHONE_MAX,
    ): Boolean =
        detailOpen &&
            hasSelection &&
            drillVisibility(contentWidth, detailOpen = true, splitMin = splitMin) == DrillVisibility.DetailOnly

    /** Column index per section: each goes under the currently shortest column, order kept. */
    fun balancedColumns(
        heights: List<Int>,
        columns: Int,
    ): List<Int> {
        require(columns >= 1) { "columns must be at least 1, was $columns" }
        val filled = IntArray(columns)
        return heights.map { h ->
            val c = filled.indices.minBy { filled[it] }
            filled[c] += h
            c
        }
    }
}
