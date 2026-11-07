package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Label + field forms that flow by the width the grid has: one column on a phone, two or three when
 * the container is wide ([PosFormGridLayout]). Every field is a [PosFormGridScope.Cell]; hints and
 * multi-line fields are [PosFormGridScope.FullRow]. No scrolling of its own; needs a bounded width
 * (do not put it inside a horizontal scroller).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosFormGrid(
    modifier: Modifier = Modifier,
    minCellWidth: Dp = PosFormGridLayout.MIN_CELL,
    maxColumns: Int = PosFormGridLayout.MAX_COLUMNS,
    content: @Composable PosFormGridScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = PosFormGridLayout.GAP
        val columns = PosFormGridLayout.columnsFor(maxWidth, minCellWidth, gap, maxColumns)
        val scope =
            remember(maxWidth, columns) {
                PosFormGridScope(PosFormGridLayout.cellWidth(maxWidth, columns, gap), maxWidth)
            }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = columns,
        ) {
            scope.content()
        }
    }
}
