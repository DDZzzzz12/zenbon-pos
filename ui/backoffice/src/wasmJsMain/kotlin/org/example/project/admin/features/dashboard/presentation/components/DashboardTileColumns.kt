package org.example.project.admin.features.dashboard.presentation.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Narrowest a dashboard tile may get: fits "2.847,60 €" at `headline-small`. */
internal val DashboardTileMinWidth: Dp = 148.dp

internal const val DashboardTileMaxColumns = 4

/**
 * How many tiles fit in a row of [width]: `floor((width + gap) / (minTileWidth + gap))`, clamped to
 * 1…[DashboardTileMaxColumns]. Never a fixed count, so no window width needs a sideways scroll.
 */
internal fun dashboardTileColumns(
    width: Dp,
    gap: Dp,
    minTileWidth: Dp = DashboardTileMinWidth,
    maxColumns: Int = DashboardTileMaxColumns,
): Int = ((width + gap) / (minTileWidth + gap)).toInt().coerceIn(1, maxColumns)
