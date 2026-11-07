package org.example.project.admin.features.floorplan

import org.example.project.admin.features.floorplan.presentation.FloorPlanEditorState
import org.example.project.admin.features.floorplan.presentation.FloorPlanSelection
import org.example.project.admin.features.floorplan.presentation.selectionInfo
import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun rTable(
    id: Long,
    w: Int?,
    h: Int?,
) = RTable(
    id = id,
    tableNumber = "3",
    seats = 6,
    locationTypeId = 1,
    locationTypeName = "Gastraum",
    tableStatus = "RESERVED",
    currentSessionId = null,
    gridX = 11,
    gridY = 5,
    rotation = 90f,
    gridWidth = w,
    gridHeight = h,
)

class FloorPlanSelectionInfoTest {
    @Test
    fun `no selection, no info`() {
        assertNull(FloorPlanEditorState().selectionInfo())
    }

    @Test
    fun `a table reports footprint, angle and a 1-based position`() {
        val state = FloorPlanEditorState(tables = listOf(rTable(1, 6, 3)), selection = FloorPlanSelection.Table(1))
        val info = state.selectionInfo()!!
        assertTrue(info.isTable)
        assertEquals(6, info.width)
        assertEquals(3, info.height)
        assertEquals(90, info.rotationDegrees)
        assertEquals(12, info.column)
        assertEquals(6, info.row)
        assertEquals(2, info.minSide)
        assertEquals("RESERVED", info.tableStatus)
        assertFalse(info.isRound)
    }

    @Test
    fun `equal sides make a round table`() {
        val state = FloorPlanEditorState(tables = listOf(rTable(1, 4, 4)), selection = FloorPlanSelection.Table(1))
        assertTrue(state.selectionInfo()!!.isRound)
    }

    @Test
    fun `a missing footprint falls back to the 2 by 2 minimum`() {
        val state = FloorPlanEditorState(tables = listOf(rTable(1, null, null)), selection = FloorPlanSelection.Table(1))
        assertEquals(2, state.selectionInfo()!!.width)
        assertEquals(2, state.selectionInfo()!!.height)
    }

    @Test
    fun `an element has a minimum side of one and no status`() {
        val element =
            RoomElement(
                id = 7,
                elementType = "WINDOW",
                gridX = 0,
                gridY = 0,
                gridWidth = 3,
                gridHeight = 1,
                locationTypeId = 1,
            )
        val info = FloorPlanEditorState(elements = listOf(element), selection = FloorPlanSelection.Element(7)).selectionInfo()!!
        assertFalse(info.isTable)
        assertEquals(1, info.minSide)
        assertNull(info.tableStatus)
        assertEquals("WINDOW", info.elementType)
    }
}
