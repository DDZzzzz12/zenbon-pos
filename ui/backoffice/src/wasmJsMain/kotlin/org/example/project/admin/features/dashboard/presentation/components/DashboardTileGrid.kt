package org.example.project.admin.features.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.theme.LocalPosLayout

/**
 * Tiles in equal-width rows; the column count comes from the width the grid really has
 * ([dashboardTileColumns]) and each row is as tall as its tallest tile. Each item receives the
 * modifier its tile must apply (`weight(1f)` + `fillMaxHeight()`).
 */
@Composable
fun DashboardTileGrid(
    items: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val gap = LocalPosLayout.current.gridGap
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = dashboardTileColumns(maxWidth, gap)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    rowItems.forEach { item -> item(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
