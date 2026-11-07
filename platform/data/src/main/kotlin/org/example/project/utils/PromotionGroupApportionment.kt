package org.example.project.utils

import java.math.BigDecimal

/**
 * THE per-VAT-key promotion split of a bill — the post-promotion gross each key's `BillVatDetail` row
 * is built from, and how much of each key's reduction is item-specific and how much bill-wide.
 *
 * Two surfaces must agree on it to the cent:
 * - `BillServiceImpl.createBillInternal` signs each key's row from [PromotionGroupSplit.postPromotionGross];
 * - `BillToInvoiceMapper` rebuilds the same split from the STORED `bill_promotion` rows to show each
 *   promotion as an EN 16931 document-level allowance (BG-20) on the Ausgangsrechnung, and weights
 *   the owner-tip removal by the same post-promotion gross the bill used.
 *
 * Item-specific deductions stay on the key of the item they were taken off. The bill-wide deduction —
 * ALL bill-wide promotions aggregated, because the largest-remainder split is not additive — is
 * apportioned by the keys' gross AFTER the item-specific deductions, in two [PromotionApportionment]
 * passes: per RATE first, then each rate's share across its own same-rate keys (ties by [split]'s
 * `tieBreaker`). Nothing is split when the bill-wide deduction or the total weight is not positive.
 */
object PromotionGroupApportionment {
    fun <K> split(
        basis: PromotionGroupBasis<K>,
        tieBreaker: Comparator<K>,
    ): PromotionGroupSplit<K> {
        val keys = basis.chargedGross.keys
        val itemDeductions = keys.associateWith { basis.itemDeductions[it] ?: BigDecimal.ZERO }
        val afterItems = keys.associateWith { basis.chargedGross.getValue(it) - itemDeductions.getValue(it) }
        val billWideShares = billWideShares(basis, afterItems, tieBreaker)
        return PromotionGroupSplit(
            itemDeductions = itemDeductions,
            billWideShares = billWideShares,
            postPromotionGross = keys.associateWith { afterItems.getValue(it) - billWideShares.getValue(it) },
        )
    }

    private fun <K> billWideShares(
        basis: PromotionGroupBasis<K>,
        afterItems: Map<K, BigDecimal>,
        tieBreaker: Comparator<K>,
    ): Map<K, BigDecimal> {
        val none = afterItems.mapValues { BigDecimal.ZERO }
        if (basis.billWideDeduction.signum() <= 0) return none
        if (afterItems.values.fold(BigDecimal.ZERO) { acc, w -> acc + w }.signum() <= 0) return none

        val keysByRate = afterItems.keys.groupBy(basis.rateOf)
        val weightsByRate =
            keysByRate.mapValues { (_, keys) -> keys.fold(BigDecimal.ZERO) { acc, key -> acc + afterItems.getValue(key) } }
        val shareByRate = PromotionApportionment.apportion(basis.billWideDeduction, weightsByRate)

        val shares = LinkedHashMap(none)
        keysByRate.forEach { (rate, keys) ->
            val rateShare = shareByRate[rate] ?: BigDecimal.ZERO
            if (rateShare.signum() == 0) return@forEach
            shares.putAll(PromotionApportionment.apportion(rateShare, keys.associateWith { afterItems.getValue(it) }, tieBreaker))
        }
        return shares
    }
}
