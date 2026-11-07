package org.example.project.features.promotion.domain

import org.example.project.features.dashboard.presentation.state.AppliedPromotion

/**
 * Client-side mirror of the server's combinability guard
 * (`BillServiceImpl.createBillInternal` / `PosErrorCode.PROMOTION_NOT_COMBINABLE`).
 *
 * The server rejects a bill that offers **more than one distinct** promotion when any of them is
 * flagged `combinable == false`. This decides the same question for a single candidate, so the till
 * can disable the row instead of letting the cashier build a bill the server will refuse.
 *
 * Note the axis: `combinable` is about sharing a bill with OTHER promotions. Repeating the *same*
 * promotion is `isMultiUse` and is handled separately — re-applying an already-applied promotion
 * adds no new distinct id, so it is never blocked here.
 *
 * @param candidateId id of the promotion the cashier is about to add.
 * @param candidateCombinable the candidate's own `combinable` flag.
 * @param applied the promotions already on the bill.
 * @return `true` when adding the candidate would violate the rule.
 */
fun blocksPromotionCombination(
    candidateId: Long,
    candidateCombinable: Boolean,
    applied: List<AppliedPromotion>,
): Boolean {
    val otherPromotions = applied.filter { it.promotionId != candidateId }
    if (otherPromotions.isEmpty()) return false
    return !candidateCombinable || otherPromotions.any { !it.combinable }
}
