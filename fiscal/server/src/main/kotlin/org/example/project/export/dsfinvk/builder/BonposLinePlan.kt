package org.example.project.export.dsfinvk.builder

import java.math.BigDecimal

/**
 * Canonical POS_ZEILE assignment of ONE bill's Bonpos block (audit H11).
 *
 * `generateLineItems` is the single numbering authority: it records here which
 * POS_ZEILE every emitted lines.csv row received. All child-file producers
 * (lines_vat, subitems, references) LOOK UP their POS_ZEILE instead of running
 * their own counters — the counters drifted as soon as a discount, an
 * item-specific promotion or a multi-rate owner tip appeared, pointing every
 * subsequent child row at the wrong Bonpos position (Anhang E Schlüsselfelder).
 *
 * Multiple child rows may share one POS_ZEILE (e.g. the owner tip's per-rate
 * lines_vat rows, or a bill-wide promotion rate line) — the spec joins on
 * (BON_ID, POS_ZEILE), it does not require child rows to be unique per line.
 */
class BonposLinePlan {
    private val itemPos = mutableMapOf<Long, Int>()
    private val discountPos = mutableMapOf<Long, Int>()
    private val voucherPos = mutableMapOf<Long, Int>()
    private var tipPos: Int? = null
    private val itemPromoPos = mutableMapOf<Long, Int>()
    private val billWidePromoRatePos = mutableMapOf<BigDecimal, Int>()
    private var receivablePos: Int? = null
    private var clearingPos: Int? = null

    fun recordItem(
        orderItemId: Long,
        posZeile: Int,
    ) {
        itemPos[orderItemId] = posZeile
    }

    fun recordDiscount(
        orderItemId: Long,
        posZeile: Int,
    ) {
        discountPos[orderItemId] = posZeile
    }

    fun recordVoucher(
        redeemId: Long,
        posZeile: Int,
    ) {
        voucherPos[redeemId] = posZeile
    }

    fun recordTip(posZeile: Int) {
        tipPos = posZeile
    }

    fun recordItemPromo(
        billPromotionId: Long,
        posZeile: Int,
    ) {
        itemPromoPos[billPromotionId] = posZeile
    }

    fun recordBillWideRate(
        vatRate: BigDecimal,
        posZeile: Int,
    ) {
        billWidePromoRatePos[vatRate.stripTrailingZeros()] = posZeile
    }

    /** T9-5: the Forderungsentstehung balancing line of an auf-Rechnung bill. */
    fun recordReceivable(posZeile: Int) {
        receivablePos = posZeile
    }

    /** T9-5: the single Forderungsauflösung line of a clearing bill. */
    fun recordClearing(posZeile: Int) {
        clearingPos = posZeile
    }

    fun itemPos(orderItemId: Long): Int =
        itemPos[orderItemId]
            ?: missing("item", "orderItem=$orderItemId")

    /**
     * Round-4 BPOS-1: lines.csv and lines_vat used to apply DIFFERENT Rabatt predicates, so a
     * sub-cent discount delta made lines_vat ask for a POS_ZEILE lines.csv never recorded — a
     * bare `getValue` surfaced that as an opaque map error that aborted the WHOLE export.
     * The lookup stays LOUD on purpose: failing soft would silently shorten lines_vat against
     * transactions_vat, trading a crash for an undetectable reconciliation break.
     */
    fun discountPos(orderItemId: Long): Int =
        discountPos[orderItemId]
            ?: error(
                "BonposLinePlan: no Rabatt POS_ZEILE recorded for orderItem=$orderItemId. " +
                    "lines.csv and lines_vat disagree on whether a Rabatt row exists — both MUST " +
                    "decide via DsfinvkExportServiceImpl.discountRabattAmount (round-4 BPOS-1).",
            )

    fun voucherPos(redeemId: Long): Int =
        voucherPos[redeemId]
            ?: missing("voucher redemption", "redeemId=$redeemId")

    fun tipPos(): Int = checkNotNull(tipPos) { "no tip line was emitted for this bill" }

    fun receivablePos(): Int = checkNotNull(receivablePos) { "no Forderungsentstehung line was emitted for this bill" }

    fun clearingPos(): Int = checkNotNull(clearingPos) { "no Forderungsaufloesung line was emitted for this bill" }

    fun itemPromoPos(billPromotionId: Long): Int =
        itemPromoPos[billPromotionId]
            ?: missing("item promotion", "billPromotionId=$billPromotionId")

    fun billWideRatePos(vatRate: BigDecimal): Int =
        billWidePromoRatePos[vatRate.stripTrailingZeros()]
            ?: missing("bill-wide promotion rate", "vatRate=$vatRate")

    /**
     * A child producer asked for a POS_ZEILE `generateLineItems` never recorded — always a
     * producer-disagreement bug, never bad data. Names the offender instead of surfacing as a
     * bare `NoSuchElementException` from deep inside the export.
     */
    private fun missing(
        kind: String,
        detail: String,
    ): Nothing =
        error(
            "BonposLinePlan: no $kind POS_ZEILE recorded for $detail. A lines.csv child producer " +
                "emitted a row lines.csv itself did not — the two must share one emission predicate.",
        )
}
