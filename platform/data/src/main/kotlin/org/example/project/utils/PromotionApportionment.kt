package org.example.project.utils

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Penny-exact apportionment of a bill-wide promotion deduction across VAT-rate groups.
 *
 * A bill-wide promotion reduces the whole receipt by a single [total] amount, but DSFinV-K needs
 * that reduction split per VAT rate (so each rate's net/gross/UST is correct). The naive
 * `total * groupGross / Σgross` split, rounded per group independently, does NOT sum back to
 * [total] — leaving a cent of drift that then disagrees across `transactions.UMS_BRUTTO`,
 * `lines_vat`, `transactions_vat` AND (critically) unbalances the TSE-signed Kassenbeleg
 * (`Σ vatDetails ≠ paidAmount`). See RA-1.
 *
 * [apportion] uses the **largest-remainder method** at scale 2: every share is rounded down to a
 * cent, then the leftover cents (`total − Σ floors`) are handed out one at a time to the groups
 * with the largest fractional remainders. The result therefore sums to EXACTLY [total] with every
 * share at scale 2.
 *
 * The SAME function (fed the same rate→gross weights) is consumed by both `BillServiceImpl`
 * (the signed `BillVatDetail` brutto) and `DsfinvkExportServiceImpl` (the exported Rabatt rows) so
 * all surfaces agree by construction. Determinism (so both callers get identical shares) is
 * guaranteed by tie-breaking equal remainders on the VAT rate ascending — independent of the input
 * map's iteration order.
 */
object PromotionApportionment {
    private val CENT = BigDecimal("0.01")

    /**
     * Distributes [total] across [weightsByRate] (VAT rate → group gross) proportionally, returning
     * a scale-2 share per rate whose sum equals [total] exactly.
     *
     * Returns all-zero shares when [total] is zero or the total weight is zero (nothing to split).
     */
    fun apportion(
        total: BigDecimal,
        weightsByRate: Map<BigDecimal, BigDecimal>,
    ): Map<BigDecimal, BigDecimal> = apportion(total, weightsByRate, Comparator.naturalOrder())

    /**
     * Generic form of [apportion] for splitting [total] across arbitrary keys — e.g. the
     * individual `Vat` entities that share ONE rate (two distinct `Vat` entities CAN share a
     * rate; `VatServiceImpl` only enforces uniqueness on `vatClass`), rather than across rates
     * directly. Used as the SECOND pass, after a first per-rate [apportion] call, to split a
     * rate's total share back across its own same-rate entities — see `BillServiceImpl`'s
     * bill-wide promotion apportionment.
     *
     * [tieBreaker] gives a deterministic order for handing out leftover cents when two keys have
     * an equal fractional remainder, since a generic key has no natural ordering to fall back on.
     */
    fun <K> apportion(
        total: BigDecimal,
        weights: Map<K, BigDecimal>,
        tieBreaker: Comparator<K>,
    ): Map<K, BigDecimal> {
        val zeroShares = weights.mapValues { BigDecimal.ZERO.setScale(2) }
        if (total.signum() == 0 || weights.isEmpty()) return zeroShares

        val totalWeight = weights.values.fold(BigDecimal.ZERO) { acc, w -> acc + w }
        if (totalWeight.signum() == 0) return zeroShares

        // Apportion on the MAGNITUDE, then re-apply the sign, so the split is sign-symmetric —
        // `apportion(-t) == -apportion(t)` exactly (A3-2). A reverse (storno) bill carries a negated
        // tip/deduction; the previous code floored the ideal share toward zero and
        // `take(leftoverCents.coerceAtLeast(0))` handed out NO leftover cent for a negative total
        // (`leftoverCents` went negative), so the negative shares summed to `total + 0.01` and the
        // exported gross drifted (Σlines_vat ≠ UMS_BRUTTO).
        val sign = total.signum()
        val absTotal = total.abs()

        data class Slice(
            val key: K,
            val floor: BigDecimal,
            val remainder: BigDecimal,
        )

        val slices =
            weights.entries.map { (key, weight) ->
                val ideal = absTotal.multiply(weight).divide(totalWeight, 10, RoundingMode.HALF_UP)
                val floor = ideal.setScale(2, RoundingMode.DOWN)
                Slice(key, floor, ideal.subtract(floor))
            }

        val floorSum = slices.fold(BigDecimal.ZERO) { acc, s -> acc + s.floor }
        // Number of leftover cents to hand out (always < number of slices, since each remainder < 1 cent).
        val leftoverCents =
            absTotal
                .subtract(floorSum)
                .divide(CENT)
                .setScale(0, RoundingMode.HALF_UP)
                .toInt()

        val result = LinkedHashMap<K, BigDecimal>()
        slices.forEach { result[it.key] = it.floor }

        slices
            .sortedWith(compareByDescending<Slice> { it.remainder }.thenBy(tieBreaker) { it.key })
            .take(leftoverCents.coerceAtLeast(0))
            .forEach { result[it.key] = result.getValue(it.key).add(CENT) }

        return if (sign < 0) result.mapValues { (_, share) -> share.negate() } else result
    }
}
