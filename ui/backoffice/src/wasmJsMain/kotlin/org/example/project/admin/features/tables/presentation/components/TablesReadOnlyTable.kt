package org.example.project.admin.features.tables.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
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
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.touchTargetSize
import org.example.project.features.settings.presentation.state.TableSortColumn
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.table.PosEditableColumn
import org.example.project.ui.components.table.PosEditableDataTable
import org.example.project.ui.components.table.PosPaginationSpec
import org.example.project.ui.components.table.PosSortState

private const val NUMBER_WEIGHT = 1f
private const val SEATS_WEIGHT = 1f
private const val LOCATION_WEIGHT = 1.7f
private const val STATUS_WEIGHT = 2f
private const val ACTIONS_WEIGHT = 1.5f

/** What a table row's actions can do: nothing for an inactive or system table, or for a read-only operator. */
private fun RTable.isActionable(canEdit: Boolean): Boolean = canEdit && isActive && !isSystem

/**
 * The medium layout's table: read-only cells, sortable headers, and a row's edit / combine / delete as
 * icon buttons. Editing happens in a dialog ([onEdit]); the whole row opens it too. An inactive table
 * is drawn at half opacity with "Inaktiv" under its status and has no actions.
 */
@Composable
fun TablesReadOnlyTable(
    tables: List<RTable>,
    sortColumn: TableSortColumn,
    sortAscending: Boolean,
    canEdit: Boolean,
    pagination: PosPaginationSpec?,
    onSortChanged: (TableSortColumn) -> Unit,
    onEdit: (RTable) -> Unit,
    onOpenCombine: (RTable) -> Unit,
    onDelete: (RTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns =
        listOf(
            PosEditableColumn(TableSortColumn.TABLE_NUMBER.name, str(AdminStringKeys.TablesColumnNumber), NUMBER_WEIGHT, sortable = true),
            PosEditableColumn(TableSortColumn.SEATS.name, str(AdminStringKeys.TablesColumnSeats), SEATS_WEIGHT, sortable = true),
            PosEditableColumn(TableSortColumn.LOCATION_TYPE.name, str(SettingsKeys.locationLabel), LOCATION_WEIGHT, sortable = true),
            PosEditableColumn(TableSortColumn.STATUS.name, str(CommonKeys.status), STATUS_WEIGHT, sortable = true),
            PosEditableColumn("actions", str(CommonKeys.actions), ACTIONS_WEIGHT, align = PosTableAlign.End),
        )
    PosEditableDataTable(
        columns = columns,
        items = tables,
        rowKey = { it.id },
        emptyText = str(SettingsKeys.noTablesFound),
        modifier = modifier,
        sortState = PosSortState(sortColumn.name, sortAscending),
        onSortChanged = { id -> TableSortColumn.entries.firstOrNull { it.name == id }?.let(onSortChanged) },
        pagination = pagination,
        rowModifier = { table ->
            val base = if (table.isActionable(canEdit)) Modifier.clickable { onEdit(table) } else Modifier
            if (table.isActive) base else base.alpha(INACTIVE_ALPHA)
        },
        rowContent = { table, _ ->
            Text(table.tableNumber, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(NUMBER_WEIGHT))
            Text(table.seats.toString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(SEATS_WEIGHT))
            Text(table.locationTypeName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(LOCATION_WEIGHT))
            TableStatusLabel(table, Modifier.weight(STATUS_WEIGHT))
            Row(Modifier.weight(ACTIONS_WEIGHT), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (table.isActionable(canEdit)) {
                    val size = touchTargetSize(32.dp)
                    PosIconButton(Icons.Default.Edit, str(CommonKeys.edit), { onEdit(table) }, size = size, iconSize = 18.dp)
                    PosIconButton(Icons.Default.Link, str(SettingsKeys.canCombineWith), { onOpenCombine(table) }, size = size, iconSize = 18.dp)
                    PosIconButton(Icons.Default.Delete, str(CommonKeys.delete), { onDelete(table) }, size = size, iconSize = 18.dp)
                }
            }
        },
    )
}

private const val INACTIVE_ALPHA = 0.5f
