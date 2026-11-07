package org.example.project.features.settings.presentation.state

import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.error.UiError

enum class TableSortColumn {
    TABLE_NUMBER,
    SEATS,
    LOCATION_TYPE,
    STATUS,
}

/**
 * Table management state.
 */
data class TableManagementState(
    val tables: List<RTable> = emptyList(),
    val isLoading: Boolean = false,
    val showAddDialog: Boolean = false,
    val editingTable: RTable? = null,
    val error: UiError? = null,
    val showUploadDialog: Boolean = false,
    val selectedLocationFilter: Long? = null,
    val searchQuery: String = "",
    val currentPage: Int = 0,
    val pageSize: Int = 15,
    val sortColumn: TableSortColumn = TableSortColumn.TABLE_NUMBER,
    val sortAscending: Boolean = true,
    val combinablePartnerIds: Set<Long> = emptySet(),
    val editingCombinablePartnerIds: Set<Long> = emptySet(),
)

fun TableManagementState.getFilteredTables(): List<RTable> {
    var result = tables

    selectedLocationFilter?.let { filterTypeId ->
        result = result.filter { it.locationTypeId == filterTypeId }
    }

    if (searchQuery.isNotBlank()) {
        result =
            result.filter {
                it.tableNumber.contains(searchQuery, ignoreCase = true)
            }
    }

    result =
        when (sortColumn) {
            TableSortColumn.TABLE_NUMBER -> {
                val numericKey: (RTable) -> Int = { table ->
                    table.tableNumber.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                }
                if (sortAscending) result.sortedBy(numericKey) else result.sortedByDescending(numericKey)
            }
            TableSortColumn.SEATS -> if (sortAscending) result.sortedBy { it.seats } else result.sortedByDescending { it.seats }
            TableSortColumn.LOCATION_TYPE ->
                if (sortAscending) {
                    result.sortedBy {
                        it.locationTypeName
                    }
                } else {
                    result.sortedByDescending { it.locationTypeName }
                }
            TableSortColumn.STATUS ->
                if (sortAscending) {
                    result.sortedBy {
                        it.tableStatus
                    }
                } else {
                    result.sortedByDescending { it.tableStatus }
                }
        }

    return result
}

fun TableManagementState.getPagedTables(): List<RTable> {
    val filtered = getFilteredTables()
    val start = (currentPage * pageSize).coerceAtMost(filtered.size)
    val end = ((currentPage + 1) * pageSize).coerceAtMost(filtered.size)
    return filtered.subList(start, end)
}

fun TableManagementState.getTotalPages(): Int {
    val filtered = getFilteredTables()
    return ((filtered.size + pageSize - 1) / pageSize).coerceAtLeast(1)
}

fun TableManagementState.getLocationCounts(): Map<Long, Int> = tables.groupingBy { it.locationTypeId }.eachCount()
