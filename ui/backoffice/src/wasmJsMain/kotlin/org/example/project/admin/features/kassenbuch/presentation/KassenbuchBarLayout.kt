package org.example.project.admin.features.kassenbuch.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.filter.PosFilterBarLayout

/**
 * Pure layout decisions of the Kassenbuch filter bar. The bar arithmetic is [PosFilterBarLayout]'s;
 * this screen only declares its minimum widths and that search is the control that gives way (into
 * the panel), as on every period-filtered screen.
 *
 * Unlike Gutscheine the panel holds nothing but the fields that left the bar, so with search in the
 * bar there is no panel and no Filter button: the one-row check is made without the button.
 */
object KassenbuchBarLayout {
    /** The narrowest the bar's search field may become before it moves into the panel instead. */
    val WIDE_SEARCH_MIN = 200.dp

    /** One-line width of the full period stepper (granularity row + `‹ label ›`); narrower, it wraps. */
    val WIDE_PERIOD_MIN = 480.dp

    /**
     * Whether search lives in the filter panel rather than the bar: always on a phone, and on a bar
     * ([barWidth], gutters excluded) too narrow for search beside the one-line period stepper.
     */
    fun searchInPanel(
        compact: Boolean,
        barWidth: Dp,
    ): Boolean {
        if (compact) return true
        return !PosFilterBarLayout.fitsInOneRow(barWidth, WIDE_SEARCH_MIN, WIDE_PERIOD_MIN, hasFilterButton = false)
    }

    /**
     * What the wide period stepper may take: what the search field (when in the bar) or the Filter
     * button (when search is in the panel, the only time there is one) leaves.
     */
    fun periodMaxWidth(
        barWidth: Dp,
        searchInPanel: Boolean,
    ): Dp =
        PosFilterBarLayout.periodMaxWidth(
            barWidth = barWidth,
            searchMin = if (searchInPanel) 0.dp else WIDE_SEARCH_MIN,
            hasFilterButton = searchInPanel,
        )
}
