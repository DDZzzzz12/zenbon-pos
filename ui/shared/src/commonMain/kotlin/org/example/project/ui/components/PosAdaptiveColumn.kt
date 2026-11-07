package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A [PosTableColumn] plus the place its cell takes in the card form of the row.
 *
 * [hideInTableBelow]: in the **table** form the column is left out while the pane is narrower than
 * this, so a low-value column gives way before the whole table falls back to cards (NEW-06). The
 * card form always shows every column. `0.dp` (the default) never hides it.
 */
data class PosAdaptiveColumn(
    val column: PosTableColumn,
    val role: PosCardRole = PosCardRole.Detail,
    val hideInTableBelow: Dp = 0.dp,
)
