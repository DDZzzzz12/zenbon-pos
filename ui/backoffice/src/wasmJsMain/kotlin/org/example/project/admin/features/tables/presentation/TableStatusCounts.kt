package org.example.project.admin.features.tables.presentation

import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.rTable.TableStatus

/** The header's "11 Tische · 6 frei · 3 besetzt · 2 reserviert": every table counts, inactive ones too. */
data class TableStatusCounts(
    val total: Int,
    val free: Int,
    val occupied: Int,
    val reserved: Int,
)

fun List<RTable>.statusCounts(): TableStatusCounts =
    TableStatusCounts(
        total = size,
        free = count { it.tableStatus == TableStatus.FREE.name },
        occupied = count { it.tableStatus == TableStatus.OCCUPIED.name },
        reserved = count { it.tableStatus == TableStatus.RESERVED.name },
    )
