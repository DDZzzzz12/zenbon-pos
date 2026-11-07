package org.example.project.admin.features.voucher.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.filter.PosFilterBarLayout

/**
 * Pure layout decisions of the Gutscheine filter bar, kept out of the composable so they are
 * testable. The bar arithmetic is [PosFilterBarLayout]'s; this screen only declares its minimum
 * widths and that search is the control that gives way (into the panel).
 */
object VoucherBarLayout {
    /** The narrowest the bar's search field may become before it moves into the panel instead. */
    val WIDE_SEARCH_MIN = 200.dp

    /** One-line width of the full period stepper (granularity row + `‹ label ›`); narrower, it wraps. */
    val WIDE_PERIOD_MIN = 480.dp

    /**
     * Whether search lives in the filter panel rather than the bar: always on a phone, and on a page
     * too narrow for search, the one-line period stepper and the Filter button side by side.
     *
     * Decided on the PAGE width ([pageWidth], the whole screen box, never the narrower column beside
     * an open detail drawer), so opening a voucher never moves the search field out from under the
     * query that found it. With the drawer open the stepper wraps instead.
     */
    fun searchInPanel(
        compact: Boolean,
        pageWidth: Dp,
        screenPadding: Dp,
    ): Boolean {
        if (compact) return true
        val barWidth = pageWidth - screenPadding * 2
        return !PosFilterBarLayout.fitsInOneRow(barWidth, WIDE_SEARCH_MIN, WIDE_PERIOD_MIN)
    }

    /** What the wide period stepper may take: what search (when in the bar) and the Filter button leave. */
    fun periodMaxWidth(
        barWidth: Dp,
        hasPanel: Boolean,
        searchInPanel: Boolean,
    ): Dp =
        PosFilterBarLayout.periodMaxWidth(
            barWidth = barWidth,
            searchMin = if (searchInPanel) 0.dp else WIDE_SEARCH_MIN,
            hasFilterButton = hasPanel,
        )
}
