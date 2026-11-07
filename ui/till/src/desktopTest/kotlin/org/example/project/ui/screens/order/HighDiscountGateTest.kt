package org.example.project.ui.screens.order

import org.example.project.core.auth.ElevationScope
import org.example.project.core.auth.PriceAuthority
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The till's discount cap must open for exactly the principals the server lets through (T5-5).
 *
 * The till used to lift the 20 % cap on `PRICE_OVERRIDE` — the custom-price gate — while
 * `OrderServiceImpl` checks `DISCOUNT_GRANT` through [PriceAuthority.mayApplyDiscount]. A group
 * holding only `PRICE_OVERRIDE` was offered a 50 % discount and got a 403 on submit; one holding
 * only `DISCOUNT_GRANT` was refused a discount the server would have accepted.
 */
class HighDiscountGateTest {
    private val fiftyPercentOff = 0.50

    @Test
    fun `PRICE_OVERRIDE alone does not lift the cap`() {
        assertFalse(canApplyHighDiscount(SetAccessPolicy(setOf(Capability.PRICE_OVERRIDE))))
    }

    @Test
    fun `DISCOUNT_GRANT alone lifts the cap`() {
        assertTrue(canApplyHighDiscount(SetAccessPolicy(setOf(Capability.DISCOUNT_GRANT))))
    }

    @Test
    fun `the till cap and the server rule agree for every relevant capability set`() {
        val sets =
            listOf(
                emptySet(),
                setOf(Capability.PRICE_OVERRIDE),
                setOf(Capability.DISCOUNT_GRANT),
                setOf(Capability.PRICE_OVERRIDE, Capability.DISCOUNT_GRANT),
            )
        sets.forEach { held ->
            val tillOffers = maxDiscountPercent(canApplyHighDiscount(SetAccessPolicy(held))) >= 50
            assertEquals(
                PriceAuthority.mayApplyDiscount(held, fiftyPercentOff),
                tillOffers,
                "till and server disagree about a 50 % discount for $held",
            )
        }
    }

    @Test
    fun `a manager-PIN step-up lends what the steep discount needs`() {
        assertTrue(PriceAuthority.STEEP_DISCOUNT_AUTHORITY in ElevationScope.TILL_OVERRIDES)
    }
}
