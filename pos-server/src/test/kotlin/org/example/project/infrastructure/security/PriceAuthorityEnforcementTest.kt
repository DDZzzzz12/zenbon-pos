package org.example.project.infrastructure.security

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.PriceAuthority
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The price and discount authorities, which `OrderServiceImpl` gates order lines on.
 *
 * This is `NoOrphanCapabilityTest`'s exemption made honest. `PRICE_OVERRIDE` and `DISCOUNT_GRANT`
 * gate no route — they are the third gate kind, decided by the request body rather than the path —
 * and until Stage 3 that exemption was a comment asserting the enforcement existed. Now the rule is
 * a pure function and this exercises it.
 *
 * **What this does not prove:** that `OrderServiceImpl` calls it. That is visible at the call site
 * (there is no other path to a custom price or a discount in `createOrder`), but it is a review
 * property, not one asserted here — building an order end to end needs a TSE, a printer and a table
 * session, and the resulting test would be about those rather than about this rule.
 */
@DisplayName("Price and discount authority")
class PriceAuthorityEnforcementTest {
    private val manager = CapabilityBundles.forRole(EmployeeRole.MANAGER)
    private val cashier = CapabilityBundles.forRole(EmployeeRole.CASHIER)

    @Test
    fun `a shallow discount needs no authority at all`() {
        // 10% off. Preserved exactly: this always worked for anyone and must keep working, or every
        // till in the field starts rejecting ordinary discounts the day this deploys.
        assertTrue(PriceAuthority.mayApplyDiscount(cashier, 0.90))
        assertTrue(PriceAuthority.mayApplyDiscount(emptySet(), 0.90))
        assertTrue(PriceAuthority.mayApplyDiscount(emptySet(), null), "an absent discount is not a discount")
    }

    @Test
    fun `exactly twenty percent stays unprivileged`() {
        // The comparison is strictly less-than, so 0.80 itself is allowed. Asserted because it is
        // the boundary someone will "tidy" into <= one day, silently rejecting orders that have
        // always been legal.
        assertTrue(PriceAuthority.mayApplyDiscount(emptySet(), PriceAuthority.STEEP_DISCOUNT_THRESHOLD))
        assertFalse(PriceAuthority.mayApplyDiscount(emptySet(), 0.79))
    }

    @Test
    fun `a steep discount needs DISCOUNT_GRANT`() {
        assertFalse(PriceAuthority.mayApplyDiscount(cashier, 0.50), "a cashier could never do this")
        assertTrue(PriceAuthority.mayApplyDiscount(manager, 0.50), "a manager always could, and still can")
        assertTrue(PriceAuthority.mayApplyDiscount(setOf(Capability.DISCOUNT_GRANT), 0.50))
    }

    @Test
    fun `a custom price needs PRICE_OVERRIDE unless the product allows one`() {
        assertFalse(PriceAuthority.mayUseCustomPrice(cashier, productAllowsCustomPrice = false))
        assertTrue(PriceAuthority.mayUseCustomPrice(manager, productAllowsCustomPrice = false))

        // The per-product escape hatch: an open-price or weighed item needs no capability from anyone.
        assertTrue(PriceAuthority.mayUseCustomPrice(emptySet(), productAllowsCustomPrice = true))
    }

    @Test
    fun `the two authorities are independent`() {
        // The case the old single `isManagerOrHigher()` flag could not express, and therefore the one
        // that proves the split was worth making: a group may grant steep discounting at the till
        // without also handing out the ability to type any price at all.
        val discountOnly = setOf(Capability.DISCOUNT_GRANT)
        assertTrue(PriceAuthority.mayApplyDiscount(discountOnly, 0.50))
        assertFalse(PriceAuthority.mayUseCustomPrice(discountOnly, productAllowsCustomPrice = false))

        val priceOnly = setOf(Capability.PRICE_OVERRIDE)
        assertTrue(PriceAuthority.mayUseCustomPrice(priceOnly, productAllowsCustomPrice = false))
        assertFalse(PriceAuthority.mayApplyDiscount(priceOnly, 0.50))
    }

    @Test
    fun `the swap from role to capability changed nobody's answer`() {
        // The migration's whole safety claim in one assertion: for every seeded ROLE group, the
        // capability answer equals what `role.isManagerOrHigher()` used to return. Purpose-built
        // groups are excluded because they have no role to compare against.
        for (role in EmployeeRole.entries) {
            val held = CapabilityBundles.forRole(role)
            val wasPrivileged = role == EmployeeRole.MANAGER || role == EmployeeRole.ADMIN

            assertTrue(
                PriceAuthority.mayApplyDiscount(held, 0.50) == wasPrivileged,
                "$role's steep-discount answer changed",
            )
            assertTrue(
                PriceAuthority.mayUseCustomPrice(held, productAllowsCustomPrice = false) == wasPrivileged,
                "$role's custom-price answer changed",
            )
        }
    }

    @Test
    fun `no purpose-built group can price or discount`() {
        // None of the three is a till supervisor. An accountant books what the till recorded; an
        // auditor reads; an order-taker takes orders at list price.
        listOf("ACCOUNTANT", "AUDITOR", "ORDER_ONLY").forEach { code ->
            val held = CapabilityBundles.forGroup(code) ?: error("no group $code")
            assertFalse(PriceAuthority.mayApplyDiscount(held, 0.50), "$code should not discount steeply")
            assertFalse(
                PriceAuthority.mayUseCustomPrice(held, productAllowsCustomPrice = false),
                "$code should not set custom prices",
            )
        }
    }

    @Test
    fun `nothing is priced under the station account - every person prices, discount or custom price`() {
        // E-2, by attribution (owner, 2026-09-28): the station account a station JWT and a bare device
        // token act as prices nothing; a person prices on any device, the station's included.
        val pricing = EmployeeRole.entries.filter { PriceAuthority.mayPriceLinesAs(it) }.toSet()
        assertEquals(setOf(EmployeeRole.CASHIER, EmployeeRole.MANAGER, EmployeeRole.ADMIN), pricing)
    }

    @Test
    fun `any multiplier below one is a discount, one and an absent value are not`() {
        assertTrue(PriceAuthority.isDiscounted(0.90))
        assertTrue(PriceAuthority.isDiscounted(0.99))
        assertFalse(PriceAuthority.isDiscounted(1.0))
        assertFalse(PriceAuthority.isDiscounted(null))
    }
}
