package org.example.project.admin.features.tables

import org.example.project.admin.features.tables.presentation.TableStatusCounts
import org.example.project.admin.features.tables.presentation.statusCounts
import org.example.project.core.data.models.RTable
import kotlin.test.Test
import kotlin.test.assertEquals

private fun table(
    id: Long,
    status: String,
    active: Boolean = true,
) = RTable(
    id = id,
    tableNumber = id.toString(),
    seats = 4,
    locationTypeId = 1,
    locationTypeName = "Gastraum",
    tableStatus = status,
    currentSessionId = null,
    gridX = null,
    gridY = null,
    rotation = null,
    isActive = active,
)

class TableStatusCountsTest {
    @Test
    fun `counts every table by status, inactive ones included`() {
        val tables = listOf(table(1, "FREE"), table(2, "OCCUPIED"), table(3, "RESERVED"), table(4, "FREE", active = false), table(5, "OCCUPIED"))
        assertEquals(TableStatusCounts(total = 5, free = 2, occupied = 2, reserved = 1), tables.statusCounts())
    }

    @Test
    fun `an unknown status counts only in the total`() {
        assertEquals(TableStatusCounts(total = 1, free = 0, occupied = 0, reserved = 0), listOf(table(1, "MAINTENANCE")).statusCounts())
    }

    @Test
    fun `no tables`() {
        assertEquals(TableStatusCounts(0, 0, 0, 0), emptyList<RTable>().statusCounts())
    }
}
