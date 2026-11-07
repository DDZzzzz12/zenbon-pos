package org.example.project.ui.components.floorplan

import org.example.project.core.domain.enums.rTable.TableStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FloorPlanBorderTest {
    @Test
    fun `a free table has no edge line`() = assertFalse(drawsDashedStatusBorder(TableStatus.FREE))

    @Test
    fun `a reserved table keeps its dashed border`() = assertTrue(drawsDashedStatusBorder(TableStatus.RESERVED))

    @Test
    fun `an occupied table is not drawn by the dashed branch`() = assertFalse(drawsDashedStatusBorder(TableStatus.OCCUPIED))

    @Test
    fun `only an occupied table gets the solid employee-colour outline`() {
        assertTrue(drawsSolidStatusBorder(TableStatus.OCCUPIED))
        assertFalse(drawsSolidStatusBorder(TableStatus.FREE))
        assertFalse(drawsSolidStatusBorder(TableStatus.RESERVED))
    }
}
