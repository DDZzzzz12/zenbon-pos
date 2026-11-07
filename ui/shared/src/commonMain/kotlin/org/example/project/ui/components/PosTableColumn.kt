package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One column of a [PosDataTable] or [PosContentTable]. [weight] shares the row width the same way
 * `Modifier.weight` does, so columns line up regardless of cell text length.
 *
 * [sortable] only affects [PosContentTable], which renders a sortable header as a clickable label
 * with a direction arrow. [minWidth] is the narrowest this column may get before the whole table must
 * fall back to cards (see [requiredTableWidth]); [nowrap] keeps body cells on one line (ellipsis). Defaulted last, so existing positional callers are unaffected.
 */
data class PosTableColumn(
    val title: String,
    val align: PosTableAlign = PosTableAlign.Start,
    val weight: Float = 1f,
    val sortable: Boolean = false,
    val minWidth: Dp = 96.dp,
    val nowrap: Boolean = false,
)
