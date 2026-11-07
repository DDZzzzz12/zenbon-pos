package org.example.project.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** One row as a card, as [PosAdaptiveTable] draws it below its width threshold. Public so a lazy list can emit one card per item. */
@Composable
fun PosAdaptiveCard(
    columns: List<PosAdaptiveColumn>,
    row: PosContentRow,
    modifier: Modifier = Modifier,
    variant: PosCardVariant = PosCardVariant.Tonal,
) {
    val layout = remember(columns) { cardLayoutFor(columns.map { it.role }) }
    PosCard(modifier = modifier.fillMaxWidth(), onClick = row.onClick, variant = variant) { body { CardBody(columns, row, layout) } }
}
