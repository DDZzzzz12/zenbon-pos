package org.example.project.admin.features.documents.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.filter.PosFilterBarLayout

/**
 * Pure layout decisions of the Belege filter bar. Belege is a search-first archive, so search always
 * stays in the bar; it is the period stepper that moves into the panel where the row has no room.
 * The bar arithmetic (Filter button width, gaps) is [PosFilterBarLayout]'s.
 */
object DocumentBarLayout {
    /** The narrowest the bar's search field may become beside the period stepper. */
    val SEARCH_MIN = 240.dp

    /**
     * The one-line width of the five-segment stepper (Tag … Zeitraum + `‹ label ›`) with the widest
     * label, a custom `2026-10-05 – 2026-10-15` range: measured ~515dp with "Oktober 2026" in German,
     * ~65dp more for the range. Narrower, the stepper wrapped to two lines and doubled the bar's
     * height (seen at a 1090px window), so below this the period moves into the panel instead.
     */
    val PERIOD_MIN = 580.dp

    /** The bar width from which the period stepper still fits beside search and the Filter button (973dp). */
    fun minBarWidthForPeriod(): Dp = PosFilterBarLayout.minBarWidth(SEARCH_MIN, PERIOD_MIN)

    /** What the period stepper may take: what the minimum search field and the Filter button leave. */
    fun periodMaxWidth(barWidth: Dp): Dp = PosFilterBarLayout.periodMaxWidth(barWidth, SEARCH_MIN)

    /** Whether the period lives in the filter panel: always on a phone, and on a bar too narrow for it. */
    fun periodInPanel(
        compact: Boolean,
        barWidth: Dp,
    ): Boolean = compact || !PosFilterBarLayout.fitsInOneRow(barWidth, SEARCH_MIN, PERIOD_MIN)
}
