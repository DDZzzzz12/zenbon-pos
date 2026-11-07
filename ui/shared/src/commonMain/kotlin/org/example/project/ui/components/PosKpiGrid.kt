package org.example.project.ui.components

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
import androidx.compose.ui.unit.Dp

/**
 * KPI cards laid out in equal-width rows that fit the width they actually have: never a scrolling
 * strip (a wheel cannot drive one on wasm) and never weighted cards squeezed below readable width.
 * Each item receives the modifier to apply to its card (`weight(1f)` + `fillMaxHeight()`).
 */
@Composable
fun PosKpiGrid(
    items: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
    minItemWidth: Dp = PosKpiGridMath.MIN_ITEM_WIDTH,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = PosKpiGridMath.columnsFor(maxWidth, items.size, minItemWidth)
        Column(verticalArrangement = Arrangement.spacedBy(PosKpiGridMath.GAP)) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(PosKpiGridMath.GAP),
                ) {
                    rowItems.forEach { item -> item(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
