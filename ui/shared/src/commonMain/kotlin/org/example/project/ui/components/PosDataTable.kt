package org.example.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/**
 * A compact, column-driven data table: a header row over hairline-separated data rows, each cell
 * laid out by its column [PosTableColumn.weight] and aligned per [PosTableColumn.align]. Because
 * every cell uses the same weighted column and alignment, columns line up no matter how long the
 * text is — which is the point (a `SpaceBetween` row of plain Text does not).
 *
 * String cells only in v1; a composable-cell overload can come later.
 *
 * [headerContainerColor] fills the header row (Zenbon dashboards use `surface-container-highest`); unspecified keeps it transparent.
 *
 * Rows with a non-null [PosTableRow.onClick] are rendered clickable; the closure captures whatever
 * payload the caller needs (e.g. a header ID), so no generic type parameter propagates.
 */
@Composable
fun PosDataTable(
    columns: List<PosTableColumn>,
    rows: List<PosTableRow>,
    modifier: Modifier = Modifier,
    headerContainerColor: Color = Color.Unspecified,
) {
    if (columns.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().then(if (headerContainerColor.isSpecified) Modifier.background(headerContainerColor) else Modifier)) {
            columns.forEach { col ->
                PosTableHeaderText(
                    text = col.title,
                    modifier = Modifier.weight(col.weight).padding(vertical = PosSpacing.sm, horizontal = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = col.align.toTextAlign(),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        rows.forEachIndexed { index, row ->
            if (row.emphasized) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            val cells = normalizeCells(row.cells, columns.size)
            val rowModifier =
                if (row.onClick != null) {
                    Modifier.fillMaxWidth().clickable { row.onClick() }
                } else {
                    Modifier.fillMaxWidth()
                }
            // A Summe/Saldo row's label runs on over the empty cells after it ("Kassenbestand nach
            // letztem Eintrag: Ladenkasse" was cut to "Kassenbestand nac…" in a narrow first column);
            // every value keeps its own column, so the figures stay aligned under their headers.
            val spans = if (row.emphasized) emphasizedSpans(cells) else List(columns.size) { 1 }
            Row(rowModifier) {
                columns.forEachIndexed { i, col ->
                    if (spans[i] == 0) return@forEachIndexed
                    val weight = (i until i + spans[i]).sumOf { columns[it].weight.toDouble() }.toFloat()
                    // A spanning label may wrap even in a no-wrap column: it is text, not a token.
                    val nowrap = col.nowrap && spans[i] == 1
                    Text(
                        text = cells[i],
                        modifier = Modifier.weight(weight).padding(vertical = PosSpacing.sm, horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (row.emphasized) FontWeight.Bold else FontWeight.Normal,
                        textAlign = col.align.toTextAlign(),
                        maxLines = if (nowrap) 1 else Int.MAX_VALUE,
                        softWrap = !nowrap,
                        overflow = if (nowrap) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                }
            }
            if (!row.emphasized && index != rows.size - 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * How many columns each cell of an emphasized row covers: the row's **label** (its first filled cell)
 * takes the blank cells that follow it, up to the first value; those blanks get 0. Every other cell
 * keeps exactly its own column, so a value never drifts away from its header.
 */
internal fun emphasizedSpans(cells: List<String>): List<Int> {
    val spans = MutableList(cells.size) { 1 }
    val label = cells.indexOfFirst { it.isNotBlank() }
    if (label < 0) return spans
    var next = label + 1
    while (next < cells.size && cells[next].isBlank()) {
        spans[label] += 1
        spans[next] = 0
        next++
    }
    return spans
}

private fun PosTableAlign.toTextAlign(): TextAlign =
    when (this) {
        PosTableAlign.Start -> TextAlign.Start
        PosTableAlign.Center -> TextAlign.Center
        PosTableAlign.End -> TextAlign.End
    }

/**
 * Pads a short row with empty cells and truncates a long one so it always matches the column count —
 * a ragged row is a caller bug but must never crash the table.
 */
fun normalizeCells(
    cells: List<String>,
    columnCount: Int,
): List<String> =
    when {
        cells.size == columnCount -> cells
        cells.size < columnCount -> cells + List(columnCount - cells.size) { "" }
        else -> cells.take(columnCount)
    }
