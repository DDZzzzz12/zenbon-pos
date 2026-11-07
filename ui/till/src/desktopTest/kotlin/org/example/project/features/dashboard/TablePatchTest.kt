package org.example.project.features.dashboard

import org.example.project.core.data.models.RTable
import org.example.project.features.dashboard.presentation.util.patchTableStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TablePatchTest {
    private fun table(
        id: Long,
        status: String,
    ) = RTable(
        id = id,
        tableNumber = "$id",
        seats = 4,
        locationTypeId = 1L,
        locationTypeName = "Indoor",
        tableStatus = status,
        currentSessionId = null,
        gridX = 0,
        gridY = 0,
        rotation = 0f,
    )

    @Test
    fun `patches matching table amount and status`() {
        val before = listOf(table(1, "OCCUPIED"), table(2, "FREE"))
        val after =
            before.patchTableStatus(
                tableId = 1,
                status = "OCCUPIED",
                openAmount = "12.00",
                employeeNumber = "07",
                sessionId = 9L,
                isTraining = false,
            )
        assertEquals("12.00", after.first { it.id == 1L }.openAmount)
        assertEquals("07", after.first { it.id == 1L }.currentEmployeeNumber)
        assertEquals(9L, after.first { it.id == 1L }.currentSessionId)
        assertNull(after.first { it.id == 2L }.openAmount)
        assertEquals("FREE", after.first { it.id == 2L }.tableStatus)
    }

    @Test
    fun `freeing a table clears amount and session`() {
        val before = listOf(table(1, "OCCUPIED").copy(openAmount = "12.00", currentSessionId = 9L, currentEmployeeNumber = "07"))
        val after =
            before.patchTableStatus(
                tableId = 1,
                status = "FREE",
                openAmount = null,
                employeeNumber = null,
                sessionId = null,
                isTraining = false,
            )
        assertEquals("FREE", after.first().tableStatus)
        assertNull(after.first().openAmount)
        assertNull(after.first().currentSessionId)
        assertNull(after.first().currentEmployeeNumber)
    }

    @Test
    fun `unknown table id leaves list unchanged`() {
        val before = listOf(table(1, "OCCUPIED"))
        val after =
            before.patchTableStatus(
                tableId = 999,
                status = "FREE",
                openAmount = null,
                employeeNumber = null,
                sessionId = null,
                isTraining = false,
            )
        assertEquals(before, after)
    }
}
