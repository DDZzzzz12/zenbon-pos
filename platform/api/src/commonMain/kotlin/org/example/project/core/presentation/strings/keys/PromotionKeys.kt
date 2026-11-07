package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object PromotionKeys {
    val createPromotion = StringKey("promotion.create")
    val editPromotion = StringKey("promotion.edit")
    val promotionName = StringKey("promotion.name")
    val promotionDescription = StringKey("promotion.description")
    val discountType = StringKey("promotion.discount_type")
    val discountValue = StringKey("promotion.discount_value")
    val fixedAmount = StringKey("promotion.fixed_amount")
    val percentage = StringKey("promotion.percentage")
    val freeItem = StringKey("promotion.free_item")
    val scope = StringKey("promotion.scope")
    val itemSpecific = StringKey("promotion.item_specific")
    val billWide = StringKey("promotion.bill_wide")
    val targetProduct = StringKey("promotion.target_product")
    val multiUse = StringKey("promotion.multi_use")

    /**
     * Row hint on a promotion the till has disabled because combining it with the promotions
     * already on the bill is not allowed. This is the `combinable` axis — whether a promotion may
     * share a bill with OTHER promotions — not [multiUse], which is about repeating the same one.
     */
    val notCombinable = StringKey("promotion.not_combinable")

    /**
     * Shown when the server rejects a bill with `PosErrorCode.PROMOTION_NOT_COMBINABLE`, i.e. the
     * till's own guard was bypassed by a stale promotion list.
     */
    val notCombinableError = StringKey("promotion.not_combinable_error")
    val validFrom = StringKey("promotion.valid_from")
    val validUntil = StringKey("promotion.valid_until")
    val active = StringKey("promotion.active")
    val inactive = StringKey("promotion.inactive")
    val usageCount = StringKey("promotion.usage_count")
    val totalDiscountGiven = StringKey("promotion.total_discount_given")
    val applyPromotion = StringKey("promotion.apply")
    val selectPromotion = StringKey("promotion.select")
    val noActivePromotions = StringKey("promotion.no_active")
    val promotionDeduction = StringKey("promotion.deduction")
    val promotionSection = StringKey("promotion.section")
}
