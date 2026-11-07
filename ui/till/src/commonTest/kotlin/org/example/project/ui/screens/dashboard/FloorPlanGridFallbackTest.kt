package org.example.project.ui.screens.dashboard

import org.example.project.core.data.models.RTable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FloorPlanGridFallbackTest {
    private fun table(
        id: Long,
        gridX: Int?,
        gridY: Int?,
        rotation: Float? = null,
    ) = RTable(
        id = id,
        tableNumber = id.toString(),
        seats = 4L,
        locationTypeId = 1L,
        locationTypeName = "Innen",
        tableStatus = "FREE",
        currentSessionId = null,
        gridX = gridX,
        gridY = gridY,
        rotation = rotation,
    )

    @Test
    fun noPlacedTableFallsBackToTheGrid() {
        val tables = listOf(table(1, null, null), table(2, null, 5))
        assertTrue(shouldShowTableGrid(isTakeawayLocation = false, isLoading = false, tables = tables))
    }

    @Test
    fun anEmptyLocationFallsBackToTheGrid() {
        assertTrue(shouldShowTableGrid(isTakeawayLocation = false, isLoading = false, tables = emptyList()))
    }

    @Test
    fun onePlacedTableKeepsTheFloorPlan() {
        val tables = listOf(table(1, null, null), table(2, 10, 5))
        assertFalse(shouldShowTableGrid(isTakeawayLocation = false, isLoading = false, tables = tables))
    }

    @Test
    fun tablesPlacedWithoutARotationKeepTheFloorPlan() {
        val tables = (1L..8L).map { table(it, gridX = it.toInt() * 4, gridY = 2, rotation = null) }
        assertFalse(shouldShowTableGrid(isTakeawayLocation = false, isLoading = false, tables = tables))
    }

    @Test
    fun whileLoadingTheFloorPlanKeepsItsSpinner() {
        assertFalse(shouldShowTableGrid(isTakeawayLocation = false, isLoading = true, tables = emptyList()))
    }

    @Test
    fun takeawayAlwaysUsesTheGrid() {
        val tables = listOf(table(1, 10, 5, 0f))
        assertTrue(shouldShowTableGrid(isTakeawayLocation = true, isLoading = true, tables = tables))
    }
}
