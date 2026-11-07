package org.example.project.utils

import java.math.BigDecimal

/**
 * Weights an OWNER tip (DSFinV-K `TrinkgeldAG`) is apportioned by across the bill's VAT keys.
 *
 * An owner tip is taxable at the rate of the underlying supply — Anhang I p. 120 signs it in the
 * 19 % container next to the 19 % Umsatz — so it must be folded into the item rates, never into
 * key 5 ("Nicht steuerbar"), which is where an EMPLOYEE tip (`TrinkgeldAN`) belongs. A promotion
 * that discounts the items to zero changes their price, not the rate of what was supplied, so the
 * weights fall back rather than give up:
 *
 * 1. [postPromotionGross] — the per-key gross after every promotion; the normal case.
 * 2. [chargedGross] — Σ `finalPrice` per key, when a promotion took the whole bill to zero.
 * 3. [itemCount] — one unit per order line, when the items themselves are priced at zero.
 *
 * Only a bill with no items at all has no supply whose rate the tip could follow; then [resolve]
 * returns null and the caller books the tip as a key-5 row, the sole case where that is honest.
 *
 * Both `BillServiceImpl` (the signed `BillVatDetail`) and `DsfinvkExportServiceImpl` (`lines_vat`
 * and `businesscases`) MUST pick their weights through this one function, keyed per `Vat` entity
 * on the service side and per rate on the export side. The rung is chosen on the total over all
 * keys, which is the same number on both sides, so both sides always land on the same rung.
 */
object OwnerTipWeights {
    fun <K> resolve(
        postPromotionGross: Map<K, BigDecimal>,
        chargedGross: Map<K, BigDecimal>,
        itemCount: Map<K, BigDecimal>,
    ): Map<K, BigDecimal>? = listOf(postPromotionGross, chargedGross, itemCount).firstOrNull { it.hasPositiveTotal() }

    private fun Map<*, BigDecimal>.hasPositiveTotal(): Boolean = values.fold(BigDecimal.ZERO) { acc, v -> acc + v }.signum() > 0
}
