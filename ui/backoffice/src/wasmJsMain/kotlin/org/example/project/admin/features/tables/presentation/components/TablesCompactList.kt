package org.example.project.admin.features.tables.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.table.PaginationFooter
import org.example.project.ui.components.table.PosPaginationSpec
import org.example.project.ui.theme.LocalPosLayout

/**
 * The phone's table list: one 64dp+ row per table with a status stripe at its start, the table's name
 * and "Gastraum · 4 Plätze" (plus who serves it and what is open while occupied), the status as dot +
 * word and a chevron. A row opens the edit dialog when [onEdit] is non-null; an inactive table is drawn
 * at half opacity, says "Inaktiv" and stays closed.
 */
@Composable
fun TablesCompactList(
    tables: List<RTable>,
    pagination: PosPaginationSpec?,
    onEdit: ((RTable) -> Unit)?,
    emptyContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalPosLayout.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = layout.screenPadding, end = layout.screenPadding, bottom = layout.paneGap),
        verticalArrangement = Arrangement.spacedBy(layout.gridGap),
    ) {
        if (tables.isEmpty()) item(key = "empty") { emptyContent() }
        items(tables, key = { it.id }) { table ->
            TableListRow(table, onClick = onEdit?.takeIf { table.isActive && !table.isSystem }?.let { open -> { open(table) } })
        }
        if (pagination != null && pagination.totalPages > 1) item(key = "pagination") { PaginationFooter(pagination) }
    }
}

@Composable
private fun TableListRow(
    table: RTable,
    onClick: (() -> Unit)?,
) {
    val status = table.statusOrNull()
    val stripe = tableStatusColors(status).first
    val subtitle = tableSubtitle(table)
    val note = tableStatusNote(table, status)
    PosCard(
        modifier = Modifier.alpha(if (table.isActive) 1f else INACTIVE_ALPHA),
        onClick = onClick,
        variant = PosCardVariant.Plain,
        contentPadding = PaddingValues(0.dp),
    ) {
        body {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).defaultMinSize(minHeight = 64.dp)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
                Row(
                    Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = str(AdminStringKeys.TablesRowTitle, table.tableNumber),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (note != null) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TableStatusLabel(table, showNote = false)
                    if (onClick != null) {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "Gastraum · 4 Plätze". */
@Composable
private fun tableSubtitle(table: RTable): String {
    val seats = if (table.seats == 1L) str(AdminStringKeys.TablesSeatsCountOne) else str(AdminStringKeys.TablesSeatsCount, table.seats.toString())
    return "${table.locationTypeName} · $seats"
}

private const val INACTIVE_ALPHA = 0.5f
