package org.example.project.features.salesinvoice.domain.service

import java.math.BigDecimal

/**
 * One EN 16931 document-level allowance (BG-20) of a from-bill invoice — a promotion the bill took off.
 *
 * [amount] is BT-92, **net** as EN 16931 requires; the till deducted [grossAmount] from VAT-inclusive
 * prices, and [vatAmount] = gross − net by subtraction. [vatRatePercent] is BT-96 in percent units.
 */
data class DraftAllowance(
    val amount: BigDecimal,
    val vatAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val vatRatePercent: BigDecimal,
    /** BT-97, e.g. `Aktion: Happy Hour`. */
    val reason: String,
    /** BT-98, UNTDID 5189 — `95` Discount. */
    val reasonCode: String,
    /** The `bill_promotion` row this came from; null when several bill-wide promotions share one allowance. */
    val billPromotionId: Long?,
)
