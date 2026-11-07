package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The string-cell sibling of [PosAdaptiveTable]: a [PosDataTable] when there is room, cards below
 * `max(cardBelow, requiredTableWidth(columns))` (the same rule as [PosAdaptiveTable], scaled by the column weights so each column gets at least its minWidth). Emphasized rows (Saldo/Summe) have no card form; they follow the cards as one bold
 * label/value line each.
 */
@Composable
fun PosAdaptiveDataTable(
    columns: List<PosAdaptiveColumn>,
    rows: List<PosTableRow>,
    modifier: Modifier = Modifier,
    cardBelow: Dp = PosAdaptiveTableDefaults.CardBelow,
    headerContainerColor: Color = Color.Unspecified,
) {
    if (columns.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (showsAsTable(columns, maxWidth, cardBelow)) {
            // Columns with `hideInTableBelow` above this width give way first (NEW-06). A caller must
            // not hide a column a Summe/Saldo row carries its label or value in.
            val visible = visibleTableColumns(columns, maxWidth)
            val shownRows =
                if (visible.size == columns.size) {
                    rows
                } else {
                    rows.map { row -> row.copy(cells = normalizeCells(row.cells, columns.size).let { cells -> visible.map { cells[it] } }) }
                }
            PosDataTable(visible.map { columns[it].column }, shownRows, headerContainerColor = headerContainerColor)
        } else {
            val (summaries, plain) = rows.partition { it.emphasized }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PosAdaptiveTable(
                    columns = columns,
                    rows = plain.map { it.toContentRow(columns.size) },
                    cardBelow = cardBelow,
                )
                summaries.forEach { SummaryLine(splitSummary(it.cells), columns) }
            }
        }
    }
}

private fun PosTableRow.toContentRow(count: Int): PosContentRow {
    val texts = normalizeCells(cells, count)
    return PosContentRow(
        cells = texts.map { text -> { Text(text, style = MaterialTheme.typography.bodyMedium) } },
        onClick = onClick,
        blankCells = texts.indices.filter { texts[it].isBlank() }.toSet(),
    )
}

/**
 * A summary row: label and value on one bold line when it has a single value; with several values
 * (a VAT total carries net, VAT, gross and a count) the label is a heading and each value is captioned
 * with its column title, so no total is dropped.
 */
@Composable
private fun SummaryLine(
    summary: PosAdaptiveSummary,
    columns: List<PosAdaptiveColumn>,
) {
    val single = summary.values.singleOrNull()
    if (summary.values.size <= 1) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // The label takes the slack and wraps; the value (money) never wraps, so a long label
            // cannot squeeze the amount into a one-character column on a phone.
            Text(
                summary.label,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            single?.let {
                Text(it.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, softWrap = false)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(summary.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        summary.values.forEach { (index, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text =
                        columns
                            .getOrNull(index)
                            ?.column
                            ?.title
                            .orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}
