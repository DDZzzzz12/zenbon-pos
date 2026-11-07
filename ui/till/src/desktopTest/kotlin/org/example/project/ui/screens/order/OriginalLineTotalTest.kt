package org.example.project.ui.screens.order

import org.example.project.core.data.models.orderLineTotal
import org.example.project.money.Decimal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pins the strikethrough baseline in `PosEditItemPanel`'s price summary against the total it is
 * compared against.
 *
 * The panel renders a struck-through "original" price only when `totalPrice != originalTotal`.
 * `totalPrice` runs through [orderLineTotal], which discounts and rounds the unit price to cents
 * FIRST and only then multiplies by quantity — mirroring the server's one-row-per-unit
 * `finalPrice`. [originalLineTotal] rounded the whole line instead, so at quantity >= 2 with a
 * scale-4 server price the two disagreed by a cent on an item nobody had touched, and the panel
 * struck through a price that had not changed.
 *
 * These call the production helper rather than re-implementing it, so restoring the whole-line
 * order makes them fail.
 */
class OriginalLineTotalTest {
    @Test
    fun `the baseline agrees with the total shown beside it for an untouched scale-4 line`() {
        // DECIMAL(19,4) on the server, so a scale-4 price reaches the client intact.
        val price = Decimal.parse("16.8950")

        assertEquals(
            orderLineTotal(price, 2, Decimal.ONE),
            originalLineTotal(price, 2),
            "the strikethrough baseline must be computed the same way as the total it gates on",
        )
    }

    @Test
    fun `the baseline rounds per unit, as the receipt does`() {
        // round(16.8950, 2) = 16.90 per unit, on each of 2 rows -> 33.80.
        // Rounding the whole line gives 16.8950 x 2 = 33.7900 -> 33.79, a cent adrift.
        assertEquals("33.80", originalLineTotal(Decimal.parse("16.8950"), 2).toPlainString())
    }

    @Test
    fun `the same holds at quantity 3, where the whole-line order rounds up instead`() {
        // Per unit: 16.90 x 3 = 50.70. Whole line: 50.6850 -> HALF_UP -> 50.69.
        // The direction of the error is not even constant, which is why eyeballing it fails.
        assertEquals("50.70", originalLineTotal(Decimal.parse("16.8950"), 3).toPlainString())
    }

    @Test
    fun `a scale-2 price is unaffected — both rounding orders coincide there`() {
        // Guards against reading the cases above as "per-unit rounding always differs". Most
        // prices are scale 2, which is why the divergence went unnoticed.
        assertEquals("50.70", originalLineTotal(Decimal.parse("16.90"), 3).toPlainString())
    }

    @Test
    fun `quantity 1 is identical under either order`() {
        assertEquals("16.90", originalLineTotal(Decimal.parse("16.8950"), 1).toPlainString())
    }
}
