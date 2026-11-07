package org.example.project.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window-dependent layout spacing — the only spacing in Zenbon that changes with screen size.
 *
 * Read it from [LocalPosLayout]; it is provided once at the app root by `ProvideWindowSizeClass`.
 * Inside components use the fixed [PosSpacing] scale instead.
 *
 * Breakpoints are by window *width* only (Material's 600 / 840 / 1200dp), unlike
 * `isAtLeastExpanded()`, which also requires a medium height: spacing should follow how much room
 * there is across, while the expanded-layout decision guards against short landscape tablets.
 *
 * @property screenPadding outer margin of a screen.
 * @property paneGap gap between panes and page sections (list | detail, stacked cards on a page).
 * @property gridGap gap between cards and tiles in a grid (product tiles, table cards, KPI cards).
 * @property listGap gap between rows in a vertical list; the same on every width.
 * @property maxContentWidth cap for forms and detail pages, [Dp.Unspecified] for full width.
 *   Tables and grids ignore it and use the full width.
 */
data class PosLayout(
    val screenPadding: Dp,
    val paneGap: Dp,
    val gridGap: Dp,
    val listGap: Dp,
    val maxContentWidth: Dp,
) {
    /** True on phone-width windows, where patterns collapse to one row / one pane. */
    val isCompact: Boolean get() = this == Compact

    companion object {
        const val MEDIUM_MIN_WIDTH_DP = 600
        const val EXPANDED_MIN_WIDTH_DP = 840
        const val LARGE_MIN_WIDTH_DP = 1200

        /** Phone (< 600dp). */
        val Compact = PosLayout(16.dp, 12.dp, 8.dp, 8.dp, Dp.Unspecified)

        /** Small tablet, portrait till (600–839dp). */
        val Medium = PosLayout(24.dp, 16.dp, 12.dp, 8.dp, Dp.Unspecified)

        /** Landscape till tablet, laptop (840–1199dp). */
        val Expanded = PosLayout(24.dp, 24.dp, 12.dp, 8.dp, 960.dp)

        /** Desktop back office, kitchen display (≥ 1200dp). */
        val Large = PosLayout(32.dp, 24.dp, 16.dp, 8.dp, 1280.dp)

        fun forWidth(widthDp: Int): PosLayout =
            when {
                widthDp < MEDIUM_MIN_WIDTH_DP -> Compact
                widthDp < EXPANDED_MIN_WIDTH_DP -> Medium
                widthDp < LARGE_MIN_WIDTH_DP -> Expanded
                else -> Large
            }
    }
}
