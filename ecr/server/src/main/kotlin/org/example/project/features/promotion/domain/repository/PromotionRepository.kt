package org.example.project.features.promotion.domain.repository

import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion

interface PromotionRepository {
    fun getById(id: Long): Promotion?

    fun getAll(): List<Promotion>

    fun getActiveAndValid(): List<Promotion>

    fun create(
        name: String,
        description: String?,
        discountType: String,
        discountValue: String,
        scope: String,
        targetProductId: Long?,
        isMultiUse: Boolean,
        combinable: Boolean,
        validFrom: String,
        validUntil: String?,
        createdByEmployeeId: String,
    ): Promotion

    fun update(
        id: Long,
        block: Promotion.() -> Unit,
    ): Promotion?

    fun isRedeemed(promotionId: Long): Boolean

    fun getUsageCount(promotionId: Long): Long

    fun getTotalDiscountGiven(promotionId: Long): java.math.BigDecimal

    fun getBillPromotionsByBillId(billId: Long): List<BillPromotion>
}
