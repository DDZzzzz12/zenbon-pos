package org.example.project.utils

import java.math.BigDecimal

/**
 * THE owner-tip share per VAT key — one deterministic, penny-exact split for every surface.
 *
 * An OWNER tip (DSFinV-K `TrinkgeldAG`) is taxable at the rate of the underlying supply, so the bill
 * folds it into its item VAT rows. Three places need to know how much of each row is tip, and they
 * must agree to the cent:
 *
 * - `BillServiceImpl` adds the share to each signed `BillVatDetail` row (keyed per `Vat` entity);
 * - `DsfinvkExportServiceImpl` exports it as the `TrinkgeldAG` lines (keyed per rate);
 * - `BillToInvoiceMapper` takes it back OUT, because the Ausgangsrechnung covers the meal and never
 *   the tip (keyed per `Vat` id).
 *
 * The weights come from [OwnerTipWeights]; the split is two [PromotionApportionment] passes — first
 * per RATE, then each rate's share across its own same-rate keys, ties broken by [split]'s
 * `tieBreaker`. With one key per rate the second pass returns the rate share unchanged, so a
 * rate-keyed caller gets exactly the single-pass result.
 */
object OwnerTipApportionment {
    /**
     * Splits [tip] across [basis]'s keys. The shares sum to exactly [tip], each at scale 2, sign
     * carried by [tip].
     *
     * Returns null when the bill has no items at all — no supply whose rate the tip could follow —
     * and the caller books the tip as one 0 %/key-5 "Nicht steuerbar" row.
     */
    fun <K> split(
        tip: BigDecimal,
        basis: OwnerTipBasis<K>,
        tieBreaker: Comparator<K>,
    ): Map<K, BigDecimal>? {
        val weights =
            OwnerTipWeights.resolve(
                postPromotionGross = basis.postPromotionGross,
                chargedGross = basis.chargedGross,
                itemCount = basis.itemCount,
            ) ?: return null
        val keysByRate = weights.keys.groupBy(basis.rateOf)
        val weightsByRate =
            keysByRate.mapValues { (_, keys) -> keys.fold(BigDecimal.ZERO) { acc, key -> acc + weights.getValue(key) } }
        val shareByRate = PromotionApportionment.apportion(tip, weightsByRate)

        val shares = LinkedHashMap<K, BigDecimal>()
        keysByRate.forEach { (rate, keys) ->
            val rateShare = shareByRate[rate] ?: BigDecimal.ZERO
            shares.putAll(PromotionApportionment.apportion(rateShare, keys.associateWith { weights.getValue(it) }, tieBreaker))
        }
        return shares
    }
}
