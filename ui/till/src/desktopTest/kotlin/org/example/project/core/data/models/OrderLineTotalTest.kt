package org.example.project.core.data.models

import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the line-total calculation the slice exists to fix. The old form was
 *   (unitPriceWithExtras?.times(quantity)?.times(discountMultiplier))?.toLong()
 * — a Double multiply followed by truncation, on the number customers are charged.
 *
 * These assertions call the production [orderLineTotal] rather than mirroring it, so reverting
 * the calculation to the truncating form makes them fail. A test that merely re-implemented the
 * arithmetic would stay green no matter what the screen does.
 */
class OrderLineTotalTest {
    private fun lineTotal(
        unitPrice: String,
        quantity: Int,
        discount: String,
    ): String =
        orderLineTotal(
            unitPrice = Decimal.parse(unitPrice),
            quantity = quantity,
            discount = Decimal.parse(discount),
        ).toPlainString()

    @Test
    fun noDiscountIsExact() {
        assertEquals("33.80", lineTotal("16.90", 2, "1.00"))
        assertEquals("50.70", lineTotal("16.90", 3, "1.00"))
    }

    @Test
    fun aTenPercentDiscountRoundsRatherThanTruncating() {
        // 16.90 x 3 x 0.90 = 45.63 exactly.
        assertEquals("45.63", lineTotal("16.90", 3, "0.90"))
    }

    @Test
    fun theHalfCentCaseRoundsUpRatherThanBeingTruncatedAway() {
        // 16.90 x 1 x 0.85 = 14.365 -> HALF_UP -> 14.37.
        // The old .toLong() path floored this to 14.36, losing a cent on every such line.
        assertEquals("14.37", lineTotal("16.90", 1, "0.85"))
    }

    @Test
    fun aFullDiscountIsZeroNotAFloatArtefact() {
        assertEquals("0.00", lineTotal("16.90", 2, "0.00"))
    }

    @Test
    fun aSubCentServerPriceIsRoundedPerUnitAsTheServerRoundsItNotCarriedIntoTheLine() {
        // DECIMAL(19,4) on the server, so 16.8950 has to survive parsing — the deleted cents
        // bridge pinned scale at 2 and destroyed it before any arithmetic ran.
        //
        // Where it goes next is the server's call, and the server rounds it away per unit:
        // finalPrice = round(16.8950 x 1.00, 2) = 16.90, on each of 2 rows -> 33.80.
        // This assertion read 33.79 while the client rounded the whole line, which carried the
        // sub-cent one step further than the receipt does.
        assertEquals("33.80", lineTotal("16.8950", 2, "1.00"))
    }

    @Test
    fun matchesTheServersPerUnitRoundingAtQuantityGreaterThanOne() {
        // Server: round(16.90 x 0.85, 2) = 14.37, then one row per quantity -> 43.11.
        // Rounding the whole line instead gives 43.10 — a cent adrift from the receipt.
        assertEquals("43.11", lineTotal("16.90", 3, "0.85"))
    }

    @Test
    fun theTwoRoundingOrdersStillCoincideWhenTheHalfCentDoesNotAccumulate() {
        // Guards against reading the case above as "per-unit rounding is always a cent higher".
        // 16.95 x 0.85 = 14.4075 -> 14.41 per unit -> 28.82, and rounding the whole line gives
        // 33.90 x 0.85 = 28.8150 -> 28.82 too. The orders differ only for specific residues, which
        // is exactly why a quantity-1 suite never caught the mismatch.
        assertEquals("28.82", lineTotal("16.95", 2, "0.85"))
    }
}
