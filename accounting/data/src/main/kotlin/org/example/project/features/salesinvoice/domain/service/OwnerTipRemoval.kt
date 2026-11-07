package org.example.project.features.salesinvoice.domain.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.OwnerTipApportionment
import org.example.project.utils.OwnerTipBasis
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Takes an OWNER tip back out of a bill's VAT rows, so the Ausgangsrechnung covers the meal only.
 *
 * An owner tip (`TrinkgeldAG`) is taxable revenue: the bill folded it into its item VAT rows, split by
 * [OwnerTipApportionment]. The invoice documents the Bewirtung itself and never the tip, so each row
 * loses exactly the share the bill added — recomputed by THE same function, never re-derived by a
 * second formula. A row that received no share is copied verbatim; a row that did gets
 * `gross = row − share`, `net = gross / (1 + rate)` HALF_UP at scale 2 and `vat = gross − net`, the
 * same rounding `BillServiceImpl.createBillVat` and the DSFinV-K item net use.
 *
 * The result is checked per VAT key, not only in total: every row less its share must equal the
 * items at that key after promotions. A bill whose rows do not split that way — one from before the
 * tip was apportioned (its tip sits in a 0 % row) — is refused with
 * [PosErrorCode.SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE] rather than issued with a wrong breakdown.
 *
 * The weights are the bill's POST-promotion gross per key ([PromotionGroupApportionment], rebuilt from
 * the stored promotions by [BillInvoiceReconciliation]) — exactly what `createBillInternal` fed
 * [OwnerTipApportionment] — with the charged line gross as the fallback rung.
 */
object OwnerTipRemoval {
    private const val NET_SCALE = 2

    /**
     * The bill's VAT rows less the owner tip. [postPromotionGross] is the items per VAT key after every
     * promotion — the tip's weights and what each row less its share must come to.
     */
    fun mealVatRows(
        facts: BillInvoiceFacts,
        postPromotionGross: Map<String, BigDecimal>,
    ): List<BillInvoiceVatFact> {
        if (facts.ownerTip.signum() == 0) return facts.vatRows
        val shares = sharesOf(facts, postPromotionGross) ?: throw notSeparable(facts, "it has no items the tip could follow")
        requireRowsSplit(facts, shares, postPromotionGross)
        return facts.vatRows.map { row -> row.withoutShare(shares[row.vatId] ?: BigDecimal.ZERO) }
    }

    private fun sharesOf(
        facts: BillInvoiceFacts,
        postPromotionGross: Map<String, BigDecimal>,
    ): Map<String, BigDecimal>? {
        val linesByVat = facts.lines.groupBy { it.vatId }
        return OwnerTipApportionment.split(
            tip = facts.ownerTip,
            basis =
                OwnerTipBasis(
                    postPromotionGross = linesByVat.mapValues { (vatId, _) -> postPromotionGross[vatId] ?: BigDecimal.ZERO },
                    chargedGross = linesByVat.mapValues { (_, lines) -> lines.sumGross() },
                    itemCount = linesByVat.mapValues { (_, lines) -> BigDecimal(lines.size) },
                    rateOf = { vatId -> linesByVat.getValue(vatId).first().vatRateFraction },
                ),
            tieBreaker = Comparator.naturalOrder(),
        )
    }

    /** Every VAT key: its row less its tip share must be exactly its items after promotions. */
    private fun requireRowsSplit(
        facts: BillInvoiceFacts,
        shares: Map<String, BigDecimal>,
        itemsByVat: Map<String, BigDecimal>,
    ) {
        val rowKeys = facts.vatRows.map { it.vatId }
        if (rowKeys.distinct().size != rowKeys.size) {
            throw notSeparable(facts, "it has two VAT detail rows for one VAT key")
        }
        val rowTotalByVat = facts.vatRows.associate { it.vatId to it.totalAmount }
        val rateByVat = facts.vatRows.associate { it.vatId to it.vatRateFraction } + facts.lines.associate { it.vatId to it.vatRateFraction }

        val mismatches =
            (rowTotalByVat.keys + itemsByVat.keys).sorted().mapNotNull { vatId ->
                val rowTotal = rowTotalByVat[vatId] ?: BigDecimal.ZERO
                val share = shares[vatId] ?: BigDecimal.ZERO
                val items = itemsByVat[vatId] ?: BigDecimal.ZERO
                if ((rowTotal - share).compareTo(items) == 0) return@mapNotNull null
                "at ${rateByVat.getValue(vatId).percent()} % the VAT detail ${rowTotal.plain()} less the owner-tip share " +
                    "${share.plain()} is ${(rowTotal - share).plain()}, but the items there total ${items.plain()}" +
                    if (facts.promotions.isEmpty()) "" else " after promotions"
            }
        if (mismatches.isNotEmpty()) throw notSeparable(facts, mismatches.joinToString("; "))
    }

    private fun BillInvoiceVatFact.withoutShare(share: BigDecimal): BillInvoiceVatFact {
        if (share.signum() == 0) return this
        val gross = totalAmount - share
        val net = gross.divide(BigDecimal.ONE + vatRateFraction, NET_SCALE, RoundingMode.HALF_UP)
        return copy(netAmount = net, vatAmount = gross - net, totalAmount = gross)
    }

    private fun List<BillInvoiceLineFact>.sumGross(): BigDecimal = fold(BigDecimal.ZERO) { acc, line -> acc + line.grossAmount }

    private fun BigDecimal.percent(): String = multiply(BigDecimal("100")).stripTrailingZeros().toPlainString()

    private fun BigDecimal.plain(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun notSeparable(
        facts: BillInvoiceFacts,
        reason: String,
    ) = PosException(
        PosErrorCode.SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE,
        HttpStatusCode.Conflict,
        "Bill ${facts.billId} cannot be invoiced: its owner tip of ${facts.ownerTip.plain()} cannot be taken out of " +
            "its VAT details exactly ($reason). The Ausgangsrechnung covers the meal only, never the tip.",
    )
}
