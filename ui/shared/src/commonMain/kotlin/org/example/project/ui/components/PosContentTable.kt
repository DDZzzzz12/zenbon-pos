package org.example.project.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/**
 * A column-driven table whose cells are composables rather than strings — the sibling of
 * [PosDataTable], whose own KDoc says it takes string cells "in v1".
 *
 * Use this wherever a cell is money (which must go through [PosMoneyText], never a hand-formatted
 * string) or a status (which belongs in a [PosStatusChip]). [PosDataTable] stays the cheaper choice
 * when every cell really is text.
 *
 * Deliberately **not** an overload of [PosDataTable]: `List<PosTableRow>` and `List<PosContentRow>`
 * erase to the same JVM signature, which is a platform declaration clash on the jvm and android
 * targets this module also builds for.
 *
 * A sortable header is clickable and carries a direction arrow on the active column. The table
 * neither sorts nor knows how to — [onSortColumn] hands the column index back and the caller
 * re-requests, because every list in adminApp is paged server-side and a client-side sort would
 * order one page while claiming to have ordered the list.
 */
@Composable
fun PosContentTable(
    columns: List<PosTableColumn>,
    rows: List<PosContentRow>,
    modifier: Modifier = Modifier,
    sort: PosTableSort? = null,
    onSortColumn: ((Int) -> Unit)? = null,
) {
    if (columns.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            columns.forEachIndexed { index, column ->
                HeaderCell(
                    column = column,
                    isSorted = sort?.columnIndex == index,
                    ascending = sort?.ascending ?: true,
                    onClick =
                        if (column.sortable && onSortColumn != null) {
                            { onSortColumn(index) }
                        } else {
                            null
                        },
                    modifier = Modifier.weight(column.weight),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        rows.forEach { row ->
            val onClick = row.onClick
            val rowModifier =
                if (onClick != null) {
                    Modifier.fillMaxWidth().clickable { onClick() }
                } else {
                    Modifier.fillMaxWidth()
                }
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                columns.forEachIndexed { index, column ->
                    Box(
                        modifier =
                            Modifier
                                .weight(column.weight)
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = column.align.toBoxAlignment(),
                    ) {
                        row.cells.getOrNull(index)?.invoke()
                    }
                }
            }
            // Detail spans the full width rather than sitting in a column: it belongs to the row, and
            // threading it through the weights would tie it to a column layout it has nothing to do
            // with. No divider between row and detail — a line there reads as two separate rows.
            row.expanded?.let { detail ->
                Box(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) { detail() }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun HeaderCell(
    column: PosTableColumn,
    isSorted: Boolean,
    ascending: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cellModifier = if (onClick != null) modifier.clickable { onClick() } else modifier
    Row(
        modifier = cellModifier.padding(vertical = PosSpacing.sm, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = column.align.toHeaderArrangement(),
    ) {
        PosTableHeaderText(
            text = column.title,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (isSorted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isSorted) {
            Icon(
                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.padding(start = 2.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun PosTableAlign.toBoxAlignment(): Alignment =
    when (this) {
        PosTableAlign.Start -> Alignment.CenterStart
        PosTableAlign.Center -> Alignment.Center
        PosTableAlign.End -> Alignment.CenterEnd
    }

private fun PosTableAlign.toHeaderArrangement(): Arrangement.Horizontal =
    when (this) {
        PosTableAlign.Start -> Arrangement.Start
        PosTableAlign.Center -> Arrangement.Center
        PosTableAlign.End -> Arrangement.End
    }
