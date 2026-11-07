package org.example.project.admin.features.dashboard.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the dashboard's status cards (Tagesabschluss, TSE, checklist) sit, by content width
 * (docs/design/admin-dashboard-responsive). Switching between these only *moves* the section cards:
 * [AdminDashboardScreen] composes each one through a `movableContentOf` slot, so crossing a breakpoint
 * neither reloads a card nor resets its local state.
 */
internal enum class DashboardLayout {
    /** From [DashboardTwoPaneMinWidth]: the status cards in a fixed right column beside the figures. */
    TwoPane,

    /** From [DashboardStatusRowMinWidth], both status cards shown: Tagesabschluss and TSE share a row. */
    StatusRow,

    /** Everything in one column. */
    Stacked,
}

/** Content width from which the status cards sit in their own right column. */
internal val DashboardTwoPaneMinWidth: Dp = 760.dp

/** Content width from which Tagesabschluss and TSE share a row instead of stacking. */
internal val DashboardStatusRowMinWidth: Dp = 560.dp

/** @param statusPairShown both Tagesabschluss and TSE are shown; a single card never takes a row of two. */
internal fun dashboardLayoutFor(
    contentWidth: Dp,
    statusPairShown: Boolean,
): DashboardLayout =
    when {
        contentWidth >= DashboardTwoPaneMinWidth -> DashboardLayout.TwoPane
        contentWidth >= DashboardStatusRowMinWidth && statusPairShown -> DashboardLayout.StatusRow
        else -> DashboardLayout.Stacked
    }
