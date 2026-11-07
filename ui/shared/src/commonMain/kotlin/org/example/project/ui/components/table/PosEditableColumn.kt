package org.example.project.ui.components.table

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosTableAlign

/**
 * A header column of a [PosEditableDataTable]. [id] is a stable key used for sort callbacks.
 * [weight] must match the weight the caller gives the corresponding cell in `rowContent` (same
 * convention the pre-refactor table already used). [sortable] shows a sort affordance + arrow.
 * [headerInset] is start padding for the header text only; set it to the inner start padding of the
 * column's cell ([PosTableCellInset] for the editable text/number/dropdown cells) so header and values share one left edge.
 * Leave 0 for columns whose cells draw plain text at the column edge.
 */
data class PosEditableColumn(
    val id: String,
    val title: String,
    val weight: Float = 1f,
    val align: PosTableAlign = PosTableAlign.Start,
    val sortable: Boolean = false,
    val headerInset: Dp = 0.dp,
)
