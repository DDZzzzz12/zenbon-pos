package org.example.project.ui.components.filter

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/**
 * Pure width arithmetic of [PosFilterBar]'s wide primary row (search slot · period · Filter button),
 * so a screen only declares its own minimum widths and which control gives way — search into the
 * panel (Gutscheine) or the period into the panel (Belege) — and never re-derives the bar.
 *
 * The row always composes the weighted search slot, even empty, so its gap is always counted.
 */
object PosFilterBarLayout {
    /**
     * Width of the labelled wide Filter button with a two-digit count. Measured in adminApp on wasm
     * (the real `PosSecondaryButton` style, Tune icon, 40dp height, Noto Sans SC), 2026-10-21:
     * "Filter" 108.5 · "Filter (8)" 128.5 · "Filter (88)" 136.5 (DE and EN share the label);
     * ZH "筛选" 102 · "筛选 (8)" 122 · "筛选 (88)" 130. The widest is DE/EN with two digits, rounded
     * up. Two digits because a screen can hold ten or more panel filters (Belege: up to 14).
     */
    val FILTER_BUTTON_WIDTH: Dp = 137.dp

    /** The gap between the row's controls; must match `PosFilterBar`'s `Arrangement.spacedBy`. */
    val GAP: Dp = PosSpacing.md

    /** The bar width from which search (at least [searchMin]) and the period (at least [periodMin]) share the row. */
    fun minBarWidth(
        searchMin: Dp,
        periodMin: Dp,
        hasFilterButton: Boolean = true,
    ): Dp = searchMin + GAP + periodMin + buttonShare(hasFilterButton)

    /** Whether search, period and (when present) the Filter button fit one row of [barWidth]. */
    fun fitsInOneRow(
        barWidth: Dp,
        searchMin: Dp,
        periodMin: Dp,
        hasFilterButton: Boolean = true,
    ): Boolean = barWidth >= minBarWidth(searchMin, periodMin, hasFilterButton)

    /**
     * What the period control may take: [barWidth] minus the search slot ([searchMin], 0 when search
     * is not in the bar) and the Filter button, with their gaps.
     */
    fun periodMaxWidth(
        barWidth: Dp,
        searchMin: Dp,
        hasFilterButton: Boolean = true,
    ): Dp = (barWidth - searchMin - GAP - buttonShare(hasFilterButton)).coerceAtLeast(0.dp)

    private fun buttonShare(hasFilterButton: Boolean): Dp = if (hasFilterButton) FILTER_BUTTON_WIDTH + GAP else 0.dp
}
