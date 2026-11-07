package org.example.project.features.promotion.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** emu16 NOTE-11: the promotion picker printed the server's raw "5.00" / "10.00%". */
class PromotionValueDisplayTest {
    @Test
    fun `a fixed amount is shown through the money formatter`() {
        assertEquals("5,00 €", formatPromotionValue("FIXED_AMOUNT", "5.00", "EUR"))
    }

    @Test
    fun `a percentage is shown through the percent formatter`() {
        assertEquals("10 %", formatPromotionValue("PERCENTAGE", "10.00", "EUR"))
        assertEquals("12,5 %", formatPromotionValue("PERCENTAGE", "12.50", "EUR"))
    }

    @Test
    fun `a free item has no value - the caller shows its localized label`() {
        assertNull(formatPromotionValue("FREE_ITEM", "0", "EUR"))
    }
}
