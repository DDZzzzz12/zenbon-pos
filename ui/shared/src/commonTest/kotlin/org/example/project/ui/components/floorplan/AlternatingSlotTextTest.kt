package org.example.project.ui.components.floorplan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlternatingSlotTextTest {
    @Test
    fun bothPresentAlternatesOnThePhase() {
        assertEquals("07", alternatingSlotText(showAmount = false, employee = "07", amount = "12,00 €"))
        assertEquals("12,00 €", alternatingSlotText(showAmount = true, employee = "07", amount = "12,00 €"))
    }

    @Test
    fun onlyEmployeeShowsStaticallyRegardlessOfPhase() {
        assertEquals("07", alternatingSlotText(showAmount = true, employee = "07", amount = null))
        assertEquals("07", alternatingSlotText(showAmount = false, employee = "07", amount = null))
    }

    @Test
    fun onlyAmountShowsStaticallyRegardlessOfPhase() {
        assertEquals("12,00 €", alternatingSlotText(showAmount = false, employee = null, amount = "12,00 €"))
        assertEquals("12,00 €", alternatingSlotText(showAmount = true, employee = null, amount = "12,00 €"))
    }

    @Test
    fun neitherPresentRendersNothing() {
        assertNull(alternatingSlotText(showAmount = true, employee = null, amount = null))
        assertNull(alternatingSlotText(showAmount = false, employee = null, amount = null))
    }
}
