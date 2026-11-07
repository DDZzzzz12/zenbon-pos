package org.example.project.core.data.models

import org.example.project.database.Local_r_tables
import org.example.project.database.Local_room_elements
import kotlin.test.Test
import kotlin.test.assertEquals

class RTableFromDatabaseTest {
    @Test
    fun `RTable fromDatabase maps all columns`() {
        val row =
            Local_r_tables(
                id = 5,
                table_number = "T-5",
                seats = 4,
                location_type_id = 1,
                location_type_name = "Indoor",
                is_virtual = 0,
                is_takeaway = 0,
                table_status = "OCCUPIED",
                current_session_id = 9,
                current_employee_number = "07",
                grid_x = 2,
                grid_y = 3,
                rotation = 0.0,
                grid_width = 2,
                grid_height = 2,
                is_training = 0,
                is_active = 1,
                assignment_priority = 0,
                open_amount = "48.50",
            )
        val t = row.toRTable()
        assertEquals(5L, t.id)
        assertEquals("T-5", t.tableNumber)
        assertEquals(4L, t.seats)
        assertEquals("OCCUPIED", t.tableStatus)
        assertEquals(9L, t.currentSessionId)
        assertEquals("07", t.currentEmployeeNumber)
        assertEquals("48.50", t.openAmount)
        assertEquals(2, t.gridX)
        assertEquals(true, t.isActive)
    }

    @Test
    fun `RoomElement fromDatabase maps all columns`() {
        val row =
            Local_room_elements(
                id = 2,
                element_type = "WALL",
                grid_x = 1,
                grid_y = 1,
                grid_width = 1,
                grid_height = 8,
                rotation = 0.0,
                location_type_id = 1,
                metadata = null,
            )
        val e = row.toRoomElement()
        assertEquals(2L, e.id)
        assertEquals("WALL", e.elementType)
        assertEquals(8, e.gridHeight)
        assertEquals(1L, e.locationTypeId)
    }
}
