package org.example.project.features.zBon.domain

import org.example.project.features.bill.domain.models.Bill
import java.math.BigDecimal

/**
 * THE Z-Bon Umsatz rule: what a closing stores as `totalPosRevenue`, and what the Monatsabschluss
 * re-derives from a stored closing's own bills to show where the two disagree.
 *
 *     totalPosRevenue = Σ(billAmount − promotionAmount) over the sale bills + the owner tips
 *
 * Sale bills are [DrawerCashDerivation.saleBills] of [DrawerCashDerivation.activeBills]: no Storno,
 * no cancelled original, no training bill, no receivable clearing. The owner tip is
 * [ZBonOwnerTipRule.ownerTips] over the active bills.
 *
 * **Why a stored closing can disagree with this (admin18 NOTE-21).** Up to v1.0.10 the closing
 * stored `totalPosRevenue = Σ billAmount + adminTip` — `billAmount` is the bill BEFORE its promotion
 * deduction, so every promotion (e.g. the "Punktekarte" free item) stayed inside the stored Umsatz.
 * The same closing's VAT groups (built from the bills' VAT details, which are after the promotion),
 * its payments and its Rabatte (`totalDiscounts = Σ discountAmount + promotionAmount`) all exclude
 * it. Commit ea21e86c2 (2026-08-02, first shipped in 1.1.0) started netting `promotionAmount`. A
 * pre-upgrade closing with promotions therefore stores a gross exactly Σ promotionAmount of its sale
 * bills above Netto + MwSt and above the bill-derived Umsatz the statistics show. Stored closings
 * are fiscal records and are never rewritten; the difference is shown, not corrected.
 *
 * Requires an ambient Exposed transaction (it reads bill references).
 */
object ZBonRevenueRule {
    /** Σ(billAmount − promotionAmount) — the Umsatz of [saleBills], before any owner tip. */
    fun saleRevenue(saleBills: List<Bill>): BigDecimal = saleBills.sumOf { it.billAmount - it.promotionAmount }

    /**
     * The `totalPosRevenue` a closing over [bills] gets under today's rule. [bills] is every bill
     * linked to the closing; training bills, Stornos, cancelled originals and clearings drop out here.
     */
    fun posRevenueOf(bills: List<Bill>): BigDecimal {
        val active = DrawerCashDerivation.activeBills(bills)
        return saleRevenue(DrawerCashDerivation.saleBills(active)) + ZBonOwnerTipRule.ownerTips(active)
    }
}
