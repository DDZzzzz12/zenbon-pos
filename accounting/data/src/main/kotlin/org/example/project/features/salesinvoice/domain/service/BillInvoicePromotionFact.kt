package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * One stored `bill_promotion` row — what the bill actually deducted, never re-evaluated from today's
 * promotion rules. [orderItemId] is set for an item-specific promotion (its deduction sits on that
 * item's VAT key) and null for a bill-wide one (apportioned over the keys like the bill did).
 */
data class BillInvoicePromotionFact(
    val billPromotionId: Long,
    /** The promotion's name as it stands; it becomes the allowance reason ("Aktion: Happy Hour"). */
    val name: String,
    val deductedAmount: BigDecimal,
    val orderItemId: Long?,
)
