package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A [PosContentTable] when there is room, a list of cards when there is not. The decision uses the
 * width of **the pane the table sits in**, not the window: three phone-wide columns on a desktop
 * each get the card form.
 *
 * The rule: table form only when the pane is at least `max(cardBelow, requiredTableWidth(columns))`
 * wide (counting only the columns [PosAdaptiveColumn.hideInTableBelow] leaves in at that width), so a table never squeezes a column below its [PosTableColumn.minWidth]. The requirement is
 * scaled by the column weights (each column gets `width * weight / totalWeight`, which must reach
 * its `minWidth`), see [requiredTableWidth].
 *
 * Nothing is lost in card form: every column appears (title, trailing, or a `label: value` line),
 * row clicks and expanded detail still work, and sortable columns become chips above the list.
 */
@Composable
fun PosAdaptiveTable(
    columns: List<PosAdaptiveColumn>,
    rows: List<PosContentRow>,
    modifier: Modifier = Modifier,
    cardBelow: Dp = PosAdaptiveTableDefaults.CardBelow,
    sort: PosTableSort? = null,
    onSortColumn: ((Int) -> Unit)? = null,
    /** The card form's card style; [PosCardVariant.Plain] (outlined) separates cards on a tonal surface. */
    cardVariant: PosCardVariant = PosCardVariant.Tonal,
    /**
     * Whether the card form draws the sort chips above the list. A screen that moves its phone sort
     * into its filter panel passes false; the table form's header-click sorting is unaffected.
     */
    showSortChips: Boolean = true,
) {
    if (columns.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (showsAsTable(columns, maxWidth, cardBelow)) {
            // Columns with `hideInTableBelow` above this width give way first (NEW-06); sort indices
            // are translated between the shown columns and the caller's full list.
            val visible = visibleTableColumns(columns, maxWidth)
            PosContentTable(
                columns = visible.map { columns[it].column },
                rows = if (visible.size == columns.size) rows else rows.map { row -> row.copy(cells = visible.map { row.cells[it] }, blankCells = emptySet()) },
                sort = sort?.let { s -> visible.indexOf(s.columnIndex).takeIf { it >= 0 }?.let { s.copy(columnIndex = it) } },
                onSortColumn = onSortColumn?.let { select -> { shown -> select(visible[shown]) } },
            )
        } else {
            CardList(columns, rows, sort, onSortColumn.takeIf { showSortChips }, cardVariant)
        }
    }
}

@Composable
private fun CardList(
    columns: List<PosAdaptiveColumn>,
    rows: List<PosContentRow>,
    sort: PosTableSort?,
    onSortColumn: ((Int) -> Unit)?,
    cardVariant: PosCardVariant,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onSortColumn != null && columns.any { it.column.sortable }) {
            SortChips(columns, sort, onSortColumn)
        }
        rows.forEach { row -> PosAdaptiveCard(columns, row, variant = cardVariant) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortChips(
    columns: List<PosAdaptiveColumn>,
    sort: PosTableSort?,
    onSortColumn: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        columns.forEachIndexed { index, adaptive ->
            if (!adaptive.column.sortable) return@forEachIndexed
            val selected = sort?.columnIndex == index
            FilterChip(
                selected = selected,
                onClick = { onSortColumn(index) },
                label = { Text(adaptive.column.title) },
                trailingIcon =
                    if (selected) {
                        {
                            Icon(
                                imageVector = if (sort?.ascending == false) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardBody(
    columns: List<PosAdaptiveColumn>,
    row: PosContentRow,
    layout: PosCardLayout,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { layout.titleIndex?.let { row.cells.getOrNull(it)?.invoke() } }
            layout.trailingIndex?.let { Box { row.cells.getOrNull(it)?.invoke() } }
        }
        if (layout.metaIndices.any { it !in row.blankCells }) {
            // NEW-06: short facts share one wrapping line ("Tisch 3 · Personal K001 · 14.10.2026 12:34").
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                layout.metaIndices.filterNot { it in row.blankCells }.forEach { index ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = columns[index].column.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        row.cells.getOrNull(index)?.invoke()
                    }
                }
            }
        }
        layout.detailIndices.forEach { index ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = columns[index].column.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.4f),
                )
                Box(Modifier.weight(0.6f), contentAlignment = Alignment.CenterEnd) { row.cells.getOrNull(index)?.invoke() }
            }
        }
        row.expanded?.invoke()
    }
}
