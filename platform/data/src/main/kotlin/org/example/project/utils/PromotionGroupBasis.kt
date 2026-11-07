package org.example.project.utils

import java.math.BigDecimal

/**
 * What a bill's promotions are taken off, per VAT key: the key's charged gross (Σ `finalPrice`), the
 * item-specific deductions that landed on its items, and the one aggregated bill-wide deduction.
 *
 * Keyed by whatever the caller splits into (a `Vat` entity or its id). [rateOf] exists because two
 * distinct `Vat` entities can share a rate: the bill-wide deduction is split per RATE first (== per
 * DSFinV-K UST_SCHLUESSEL), then each rate's share across its own keys.
 */
data class PromotionGroupBasis<K>(
    val chargedGross: Map<K, BigDecimal>,
    val itemDeductions: Map<K, BigDecimal>,
    val billWideDeduction: BigDecimal,
    val rateOf: (K) -> BigDecimal,
)
