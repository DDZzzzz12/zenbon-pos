package org.example.project.features.dsfinvk.data.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Divides ONE bill's STORED `BillVatDetail` net across its businesscases (Z_GV_Typ) rows, per
 * UST_SCHLUESSEL, so they sum exactly to the stored value. `transactions_vat` and the Z-Bon
 * `VatGroup` read those same stored rows (F43).
 *
 * [storedNetByKey] holds the bill's stored `BillVatDetail.netAmount`, summed per key and already
 * signed. It is the target, and nothing here re-nets it. A bill written by an older formula, or
 * one with two same-rate Vat entities, therefore still agrees with its own stored split.
 *
 * Revenue rows (Umsatz, Pfand, PfandRueckzahlung, MehrzweckgutscheinKauf) are registered by
 * gross with [addRevenue]. Every other taxed row of the bill (Rabatt, promotions, TrinkgeldAG)
 * arrives with the net its own producer already computed, via [addFixed]. [revenueNets] splits
 * the target the way `applyLineVatGroupResidual` splits a lines_vat group. Each revenue group
 * gets a provisional per-row net, and the residual `target − Σ nets` goes on the LAST revenue
 * group of the key. The [addFixed] rows keep their values.
 *
 * A key with no stored row falls back to `round(Σ gross / (1 + rate), 2)`. That covers a bill
 * missing its `BillVatDetail` rows, which the export already warns about. There is no stored
 * value to agree with there.
 *
 * Netting has to happen per bill. The earlier fix (round-3 INV-F6) netted the whole closing's
 * aggregated gross once, which equals Σ BillVatDetail only when a key has a single bill. A real
 * day with 12 bills came out 1 ct off at both 19 % and 7 %.
 *
 * Values are passed signed (negative on a storno reverse bill). [singleNet] rounds HALF_UP,
 * which is symmetric under negation, so a reversal nets to exactly the original's negation.
 */
internal class BillVatNetting<K : Any>(
    private val storedNetByKey: Map<Int, BigDecimal>,
) {
    private class RevenueGroup(
        val ustSchluessel: Int,
        val rate: BigDecimal,
        var gross: BigDecimal,
    )

    private class KeyTotals {
        var gross: BigDecimal = BigDecimal.ZERO
        var fixedNet: BigDecimal = BigDecimal.ZERO
    }

    private val revenue = linkedMapOf<K, RevenueGroup>()
    private val totals = linkedMapOf<Int, KeyTotals>()

    /** Registers a revenue row's signed [gross] under [aggregateKey]; its net is derived in [revenueNets]. */
    fun addRevenue(
        aggregateKey: K,
        ustSchluessel: Int,
        rate: BigDecimal,
        gross: BigDecimal,
    ) {
        val rounded = gross.setScale(2, RoundingMode.HALF_UP)
        val group = revenue.getOrPut(aggregateKey) { RevenueGroup(ustSchluessel, rate, BigDecimal.ZERO) }
        group.gross += rounded
        totalsOf(ustSchluessel).gross += rounded
    }

    /** Registers a taxed row whose signed [net] its producer has already fixed. */
    fun addFixed(
        ustSchluessel: Int,
        gross: BigDecimal,
        net: BigDecimal,
    ) {
        val keyTotals = totalsOf(ustSchluessel)
        keyTotals.gross += gross.setScale(2, RoundingMode.HALF_UP)
        keyTotals.fixedNet += net
    }

    /** The signed net for each revenue aggregate key, residual-corrected to the stored net per UST_SCHLUESSEL. */
    fun revenueNets(): Map<K, BigDecimal> {
        val nets = linkedMapOf<K, BigDecimal>()
        revenue.forEach { (key, group) -> nets[key] = singleNet(group.gross, group.rate) }
        revenue.entries
            .groupBy { it.value.ustSchluessel }
            .forEach { (ustSchluessel, groups) ->
                val keyTotals = totalsOf(ustSchluessel)
                val target = storedNetByKey[ustSchluessel] ?: singleNet(keyTotals.gross, groups.first().value.rate)
                val actual = groups.fold(keyTotals.fixedNet) { acc, entry -> acc + nets.getValue(entry.key) }
                val residual = target - actual
                if (residual.signum() != 0) {
                    val absorber = groups.last().key
                    nets[absorber] = nets.getValue(absorber) + residual
                }
            }
        return nets
    }

    private fun totalsOf(ustSchluessel: Int): KeyTotals = totals.getOrPut(ustSchluessel) { KeyTotals() }

    companion object {
        /**
         * The single netting `BillServiceImpl.createBillVat` applies to a rate group:
         * `gross / (1 + rate)` at scale 2, HALF_UP. [rate] is a fraction (0.19), as `Vat.vatRate` stores it.
         */
        fun singleNet(
            gross: BigDecimal,
            rate: BigDecimal,
        ): BigDecimal = gross.divide(BigDecimal.ONE + rate, 2, RoundingMode.HALF_UP)
    }
}
