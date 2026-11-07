package org.example.project.utils

import java.math.BigDecimal

/**
 * A bill's promotions per VAT key, as [PromotionGroupApportionment] computes them. Every map carries
 * every key of the basis, in the basis' order.
 *
 * [postPromotionGross] is `charged − item deductions − bill-wide share` — the gross the bill signed
 * into that key's `BillVatDetail` before any owner tip was folded in.
 */
data class PromotionGroupSplit<K>(
    val itemDeductions: Map<K, BigDecimal>,
    val billWideShares: Map<K, BigDecimal>,
    val postPromotionGross: Map<K, BigDecimal>,
)
