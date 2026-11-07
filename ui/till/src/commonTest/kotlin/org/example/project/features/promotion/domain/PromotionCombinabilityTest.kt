package org.example.project.features.promotion.domain

import org.example.project.features.dashboard.presentation.state.AppliedPromotion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The till-side mirror of the server guard. Each case is one the server's
 * `PromotionCombinabilityTest` also covers, so the two levels cannot drift apart silently.
 */
class PromotionCombinabilityTest {
    private fun applied(
        id: Long,
        combinable: Boolean = true,
        orderItemId: Long? = null,
    ) = AppliedPromotion(
        promotionId = id,
        promotionName = "P$id",
        discountType = "FIXED_AMOUNT",
        discountValue = "1.00",
        scope = "BILL_WIDE",
        combinable = combinable,
        orderItemId = orderItemId,
    )

    @Test
    fun nonCombinableAloneIsAllowed() {
        assertFalse(blocksPromotionCombination(1L, candidateCombinable = false, applied = emptyList()))
    }

    @Test
    fun combinableAlongsideCombinableIsAllowed() {
        assertFalse(blocksPromotionCombination(2L, candidateCombinable = true, applied = listOf(applied(1L))))
    }

    @Test
    fun nonCombinableCandidateIsBlockedWhenAnotherPromotionIsApplied() {
        assertTrue(blocksPromotionCombination(2L, candidateCombinable = false, applied = listOf(applied(1L))))
    }

    @Test
    fun anyCandidateIsBlockedWhenAnAppliedPromotionIsNonCombinable() {
        assertTrue(
            blocksPromotionCombination(2L, candidateCombinable = true, applied = listOf(applied(1L, combinable = false))),
        )
    }

    /** Repeating the SAME promotion adds no distinct id — that is the `isMultiUse` axis. */
    @Test
    fun reapplyingTheSameNonCombinablePromotionIsNotBlocked() {
        assertFalse(
            blocksPromotionCombination(
                candidateId = 1L,
                candidateCombinable = false,
                applied = listOf(applied(1L, combinable = false, orderItemId = 10L)),
            ),
        )
    }
}
