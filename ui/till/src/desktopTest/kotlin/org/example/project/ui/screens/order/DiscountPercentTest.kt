package org.example.project.ui.screens.order

import org.example.project.money.Decimal
import org.example.project.money.Percentage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the discount field's parsing in `PosEditItemPanel`.
 *
 * The field edits percent OFF; `Items.discount` stores the MULTIPLIER the server persists in
 * `OrderItemTable.discount`, a `DECIMAL(4,2)`. The old parse was `discount.toIntOrNull() ?: 0`,
 * so anything that was not a bare integer — "12.5", "12,5", "abc" — collapsed to 0 % off and the
 * discount vanished with no message at all.
 *
 * Fractional percents are rejected rather than accepted, deliberately: 12.5 % off is multiplier
 * 0.875, scale 3, which `DECIMAL(4,2)` cannot hold. See [discountPercentOrNull]'s own comment.
 */
class DiscountPercentTest {
    @Test
    fun `a fractional percent is rejected instead of silently becoming no discount`() {
        assertNull(discountPercentOrNull("12.5", maxPercent = 100))
    }

    @Test
    fun `the old toIntOrNull route turned that same input into zero percent off`() {
        // Documents the defect rather than asserting its absence: this is what the panel did.
        assertEquals(0, "12.5".toIntOrNull() ?: 0)
    }

    @Test
    fun `a German decimal comma is normalised before parsing, then judged on its value`() {
        assertNull(discountPercentOrNull("12,5", maxPercent = 100))
        assertEquals("15", discountPercentOrNull("15,0", maxPercent = 100)?.toPlainString())
    }

    @Test
    fun `a whole percent is accepted`() {
        assertEquals("15", discountPercentOrNull("15", maxPercent = 100)?.toPlainString())
    }

    @Test
    fun `a whole percent written with a trailing zero is accepted — the test is on value, not scale`() {
        assertEquals("15", discountPercentOrNull("15.0", maxPercent = 100)?.toPlainString())
    }

    @Test
    fun `an empty field means no discount rather than an error`() {
        assertEquals(Decimal.ZERO, discountPercentOrNull("", maxPercent = 100))
        assertEquals(Decimal.ZERO, discountPercentOrNull("   ", maxPercent = 100))
    }

    @Test
    fun `a non-numeric value is rejected`() {
        assertNull(discountPercentOrNull("abc", maxPercent = 100))
    }

    @Test
    fun `a percent above the operator's cap is rejected, and the cap itself accepted`() {
        assertNull(discountPercentOrNull("25", maxPercent = 20))
        assertEquals("20", discountPercentOrNull("20", maxPercent = 20)?.toPlainString())
    }

    @Test
    fun `a negative percent is rejected`() {
        assertNull(discountPercentOrNull("-5", maxPercent = 100))
    }

    @Test
    fun `the cap depends on the operator's permission`() {
        assertEquals(100, maxDiscountPercent(canApplyHighDiscount = true))
        assertEquals(20, maxDiscountPercent(canApplyHighDiscount = false))
    }

    @Test
    fun `an accepted percent converts to a multiplier the server's column can hold`() {
        val percent = discountPercentOrNull("15", maxPercent = 100)
        val multiplier = Percentage.ofPercent(percent ?: Decimal.ZERO).complement().toFraction()

        assertEquals("0.85", multiplier.toPlainString())
        assertEquals(2, multiplier.scale, "OrderItemTable.discount is DECIMAL(4,2)")
    }
}
