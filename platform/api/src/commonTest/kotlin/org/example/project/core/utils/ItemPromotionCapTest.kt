package org.example.project.core.utils

import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemPromotionCapTest {
    private fun d(s: String) = Decimal.parse(s)

    @Test
    fun `a deduction that fits the rest is returned unchanged, scale included`() {
        val deduction = d("8.0000")
        val capped = ItemPromotionCap.cap(deduction, itemPrice = d("10.0000"), alreadyDeducted = d("0"))
        assertTrue(capped.equalsExact(deduction), "got ${capped.toPlainString()}")
    }

    @Test
    fun `a deduction past the rest is capped at what earlier promotions left`() {
        assertEquals(d("2.00"), ItemPromotionCap.cap(d("8.00"), itemPrice = d("10.00"), alreadyDeducted = d("8.00")))
    }

    @Test
    fun `nothing left deducts zero`() {
        assertEquals(Decimal.ZERO, ItemPromotionCap.cap(d("5.00"), itemPrice = d("10.00"), alreadyDeducted = d("10.00")))
    }

    @Test
    fun `the rest is never negative`() {
        assertEquals(Decimal.ZERO, ItemPromotionCap.remaining(itemPrice = d("10.00"), alreadyDeducted = d("16.00")))
    }

    @Test
    fun `the first promotion on an item is bounded by the price`() {
        assertEquals(d("10.00"), ItemPromotionCap.cap(d("12.00"), itemPrice = d("10.00"), alreadyDeducted = d("0")))
    }
}
