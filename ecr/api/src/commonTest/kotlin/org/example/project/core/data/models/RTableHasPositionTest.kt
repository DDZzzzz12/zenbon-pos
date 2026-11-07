package org.example.project.core.data.models

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RTableHasPositionTest {
    private fun table(
        gridX: Int?,
        gridY: Int?,
        rotation: Float?,
    ) = RTable(
        id = 1L,
        tableNumber = "1",
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
    fun aTableWithoutAStoredRotationStillHasAPosition() {
        assertTrue(table(gridX = 3, gridY = 4, rotation = null).hasPosition())
    }

    @Test
    fun aRotatedTableHasAPosition() {
        assertTrue(table(gridX = 3, gridY = 4, rotation = 90f).hasPosition())
    }

    @Test
    fun aMissingGridXMeansNoPosition() {
        assertFalse(table(gridX = null, gridY = 4, rotation = 0f).hasPosition())
    }

    @Test
    fun aMissingGridYMeansNoPosition() {
        assertFalse(table(gridX = 3, gridY = null, rotation = 0f).hasPosition())
    }
}
