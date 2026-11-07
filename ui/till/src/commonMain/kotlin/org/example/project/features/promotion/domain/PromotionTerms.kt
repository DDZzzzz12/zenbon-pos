package org.example.project.features.promotion.domain

/**
 * What a promotion deducts and where: the inputs of the till's deduction estimate. Mirrors the
 * promotion's `discountType`/`discountValue`/`scope` strings and the order item an ITEM_SPECIFIC
 * promotion is applied to.
 */
data class PromotionTerms(
    val discountType: String,
    val discountValue: String,
    val scope: String,
    val orderItemId: Long?,
)
