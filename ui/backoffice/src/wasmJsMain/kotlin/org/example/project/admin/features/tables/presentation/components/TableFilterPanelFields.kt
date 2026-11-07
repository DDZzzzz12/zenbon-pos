package org.example.project.admin.features.tables.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.features.tables.presentation.TablePanelFilter
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.state.TableSortColumn
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.filter.PosFilterDraft
import org.example.project.ui.components.filter.PosFilterPicker
import org.example.project.ui.components.filter.PosFilterSortField

/**
 * The Tische filter panel body: the location (where [showLocation]: on a phone, or once the draft
 * changed it) and the sort column with its direction. Both edit [draft] only.
 */
@Composable
fun TableFilterPanelFields(
    draft: PosFilterDraft<TablePanelFilter>,
    locations: List<TableLocationOption>,
    totalCount: Int,
    showLocation: Boolean,
) {
    if (showLocation) LocationField(draft, locations, totalCount)
    SortField(draft)
}

@Composable
private fun LocationField(
    draft: PosFilterDraft<TablePanelFilter>,
    locations: List<TableLocationOption>,
    totalCount: Int,
) {
    val selectedId = draft.value.locationTypeId
    val allLabel = "${str(CommonKeys.all)} ($totalCount)"
    val value = locations.firstOrNull { it.id == selectedId }?.label ?: allLabel
    PosFilterPicker(label = str(SettingsKeys.locationLabel), value = value) { close ->
        PosDropdownItem(
            text = allLabel,
            selected = selectedId == null,
            minHeight = adminTouchSize(40.dp),
            onClick = {
                close()
                draft.value = draft.value.copy(locationTypeId = null)
            },
        )
        locations.forEach { location ->
            PosDropdownItem(
                text = location.label,
                selected = location.id == selectedId,
                minHeight = adminTouchSize(40.dp),
                onClick = {
                    close()
                    draft.value = draft.value.copy(locationTypeId = location.id)
                },
            )
        }
    }
}

@Composable
private fun SortField(draft: PosFilterDraft<TablePanelFilter>) {
    PosFilterSortField(
        keys = TableSortColumn.entries,
        selectedKey = draft.value.sortColumn,
        ascending = draft.value.sortAscending,
        keyLabel = { tableSortColumnLabel(it) },
        // Changing the column keeps the direction.
        onChange = { column, ascending -> draft.value = draft.value.copy(sortColumn = column, sortAscending = ascending) },
    )
}

/** The column's own header label, as in the table and the former sort chips. */
@Composable
fun tableSortColumnLabel(column: TableSortColumn): String =
    when (column) {
        TableSortColumn.TABLE_NUMBER -> str(SettingsKeys.tableNumber)
        TableSortColumn.SEATS -> str(SettingsKeys.seats)
        TableSortColumn.LOCATION_TYPE -> str(SettingsKeys.locationLabel)
        TableSortColumn.STATUS -> str(CommonKeys.status)
    }
