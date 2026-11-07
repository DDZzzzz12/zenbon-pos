package org.example.project.utils

import java.math.BigDecimal

/**
 * What an OWNER tip is apportioned over: the three weight rungs [OwnerTipWeights] chooses between,
 * keyed by whatever the caller splits into (a `Vat` entity, its id, or a bare rate), plus the rate
 * each key belongs to.
 *
 * [rateOf] exists because two distinct `Vat` entities can share a rate: the tip is first split per
 * RATE (== per DSFinV-K UST_SCHLUESSEL), then each rate's share across its own keys.
 */
data class OwnerTipBasis<K>(
    val postPromotionGross: Map<K, BigDecimal>,
    val chargedGross: Map<K, BigDecimal>,
    val itemCount: Map<K, BigDecimal>,
    val rateOf: (K) -> BigDecimal,
)
