package org.example.project.utils

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An OWNER tip (TrinkgeldAG) is taxable at the rate of the underlying supply (DSFinV-K Anhang I
 * p. 120 shows it at 19 %). It is apportioned across the bill's VAT rates by weight, and the weight
 * ladder must never leave a taxable tip without a rate to follow: post-promotion gross first, the
 * charged item gross when a promotion discounted the bill to zero, and the item count when the
 * items themselves are priced at zero. Only a bill with no items at all has no supply, and only
 * then does the ladder give up.
 */
class OwnerTipWeightsTest {
    private val rate19 = BigDecimal("0.19")
    private val rate7 = BigDecimal("0.07")

    @Test
    fun `uses the post-promotion gross while it is positive`() {
        val weights =
            OwnerTipWeights.resolve(
                postPromotionGross = mapOf(rate19 to BigDecimal("5.00"), rate7 to BigDecimal("10.00")),
                chargedGross = mapOf(rate19 to BigDecimal("10.00"), rate7 to BigDecimal("10.00")),
                itemCount = mapOf(rate19 to BigDecimal.ONE, rate7 to BigDecimal.ONE),
            )
        assertEquals(mapOf(rate19 to BigDecimal("5.00"), rate7 to BigDecimal("10.00")), weights)
    }

    @Test
    fun `falls back to the charged gross when a promotion discounted the bill to zero`() {
        val weights =
            OwnerTipWeights.resolve(
                postPromotionGross = mapOf(rate19 to BigDecimal("0.00"), rate7 to BigDecimal("0.00")),
                chargedGross = mapOf(rate19 to BigDecimal("15.00"), rate7 to BigDecimal("5.00")),
                itemCount = mapOf(rate19 to BigDecimal.ONE, rate7 to BigDecimal.ONE),
            )
        assertEquals(mapOf(rate19 to BigDecimal("15.00"), rate7 to BigDecimal("5.00")), weights)
    }

    @Test
    fun `falls back to the item count when the items are priced at zero`() {
        val weights =
            OwnerTipWeights.resolve(
                postPromotionGross = mapOf(rate19 to BigDecimal("0.00"), rate7 to BigDecimal("0.00")),
                chargedGross = mapOf(rate19 to BigDecimal("0.00"), rate7 to BigDecimal("0.00")),
                itemCount = mapOf(rate19 to BigDecimal("3"), rate7 to BigDecimal.ONE),
            )
        assertEquals(mapOf(rate19 to BigDecimal("3"), rate7 to BigDecimal.ONE), weights)
    }

    @Test
    fun `gives up only when the bill has no items at all`() {
        val weights =
            OwnerTipWeights.resolve<BigDecimal>(
                postPromotionGross = emptyMap(),
                chargedGross = emptyMap(),
                itemCount = emptyMap(),
            )
        assertNull(weights)
    }
}
