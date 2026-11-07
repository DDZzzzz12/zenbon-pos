package org.example.project.features.dashboard.presentation.util

import org.example.project.core.data.models.RTable

/**
 * Returns a copy of the list with table [tableId] patched from an SSE table-status
 * update. Non-matching tables are returned unchanged. Fields not carried by the push
 * (seats, position, isActive, locationType) are preserved.
 */
fun List<RTable>.patchTableStatus(
    tableId: Long,
    status: String,
    openAmount: String?,
    employeeNumber: String?,
    sessionId: Long?,
    isTraining: Boolean,
): List<RTable> =
    map { table ->
        if (table.id != tableId) {
            table
        } else {
            table.copy(
                tableStatus = status,
                openAmount = openAmount,
                currentEmployeeNumber = employeeNumber,
                currentSessionId = sessionId,
                isTraining = isTraining,
            )
        }
    }
