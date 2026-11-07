package org.example.project.admin.features.product.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.layout.PaneBreakpoints

/**
 * Which of the three Produkte layouts the screen's content width calls for:
 * [Large] = category sidebar + table, [Medium] = category chips + table, [Compact] = filter bar + list rows.
 */
enum class ProductLayoutMode {
    Large,
    Medium,
    Compact,
    ;

    companion object {
        /**
         * Sidebar 280 + divider 1 + row padding 24 + actions 76 + the five columns (weights 2.6/1.4/1.8/1.4/1.3 = 8.5).
         * The tightest column is price: 104dp Money min + 8 margin = 112 -> 112 * 8.5 / 1.4 = 680dp
         * (status needs 92 -> 601, number 96 -> 583, name 142 -> 464). 280 + 1 + 24 + 76 + 680 = 1061 -> 1060.
         */
        val LARGE_MIN_WIDTH: Dp = 1060.dp

        /**
         * [touchExtra] is what the two row actions grow by on a touch-sized window (36 -> 48dp each).
         * A short window (landscape phone) is always [Compact], whatever its width.
         */
        fun of(
            contentWidth: Dp,
            contentHeight: Dp,
            touchExtra: Dp = 0.dp,
        ): ProductLayoutMode =
            when {
                contentWidth < PaneBreakpoints.PHONE_MAX || PaneBreakpoints.isShortWindow(contentHeight) -> Compact
                contentWidth < LARGE_MIN_WIDTH + touchExtra -> Medium
                else -> Large
            }
    }
}
