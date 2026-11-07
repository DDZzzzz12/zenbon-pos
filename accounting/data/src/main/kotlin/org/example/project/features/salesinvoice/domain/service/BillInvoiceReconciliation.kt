package org.example.project.features.salesinvoice.domain.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.PromotionApportionment
import org.example.project.utils.PromotionGroupApportionment
import org.example.project.utils.PromotionGroupBasis
import org.example.project.utils.PromotionGroupSplit
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reconciles a bill's items, promotions, owner tip and signed VAT rows into what its Ausgangsrechnung
 * carries: lines at the price charged, each promotion as an EN 16931 document-level allowance (BG-20),
 * and a VAT breakdown equal to the bill's rows less the owner-tip share — to the cent, per VAT key.
 *
 * ## Where every amount comes from
 *
 * Only STORED data: the order items' `final_price`, the `bill_promotion.deducted_amount` rows and the
 * `bill_vat_detail` rows. No promotion rule is re-evaluated. The per-key split of the deductions is
 * [PromotionGroupApportionment] — THE function `createBillInternal` signed the rows with — so an
 * item-specific deduction sits on its item's VAT key and the bill-wide ones are apportioned exactly as
 * the bill apportioned them.
 *
 * ## Gross to net
 *
 * The till deducts from VAT-inclusive prices; EN 16931 states an allowance net (BT-92) and puts its
 * VAT into the group's taxable amount (BT-116 = Σ line net − Σ allowances). Per VAT key the
 * allowances' net is therefore the difference of two splits made by the same rule the rows use
 * (`net = round(gross / (1 + rate), 2, HALF_UP)`, VAT by subtraction):
 *
 *     allowance net = net(Σ items at the key) − the meal row's net
 *     allowance VAT = allowance gross − allowance net
 *
 * so the key's taxable amount stays exactly the bill row's net and its VAT the row's VAT. Several
 * allowances on one key share that net by their gross ([PromotionApportionment], penny-exact).
 * All bill-wide promotions on one key form ONE allowance, because the bill apportioned their SUM —
 * splitting that share back per promotion would name amounts no receipt printed.
 *
 * A bill whose stored promotions do not reproduce its rows exactly (a promotion on an item that is no
 * longer on the bill, deductions that do not add up to its promotion total, a VAT key deducted below
 * zero, or a row that disagrees with its items after promotions) is refused with [PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE]
 * rather than invoiced with a breakdown that disagrees with the Kassenbeleg.
 *
 * A bill whose bill-wide promotions were capped at its total (`scaleDeductionsToCap`) is NOT refused
 * for that: the whole bill was deducted, so every key's post-promotion gross is zero both in the signed
 * rows and in this split, and it becomes a zero invoice whose allowances equal the line nets.
 */
object BillInvoiceReconciliation {
    /** UNTDID 5189 `95` — Discount. */
    const val REASON_CODE_DISCOUNT = "95"

    private const val NET_SCALE = 2
    private val HUNDRED = BigDecimal("100")

    fun reconcile(facts: BillInvoiceFacts): ReconciledBillInvoice {
        val split = promotionSplit(facts)
        requireNoNegativeKey(facts, split)
        val mealRows = OwnerTipRemoval.mealVatRows(facts, split.postPromotionGross)
        if (facts.promotions.isEmpty()) return ReconciledBillInvoice(mealRows, emptyList())
        requireRowsMatch(facts, mealRows, split)
        return ReconciledBillInvoice(mealRows, allowancesOf(facts, mealRows, split))
    }

    private fun promotionSplit(facts: BillInvoiceFacts): PromotionGroupSplit<String> {
        val linesByVat = facts.lines.groupBy { it.vatId }
        val lineByItem = facts.lines.associateBy { it.orderItemId }
        val itemDeductions = LinkedHashMap<String, BigDecimal>()
        facts.promotions.filter { it.orderItemId != null }.forEach { promotion ->
            val line =
                lineByItem[promotion.orderItemId]
                    ?: throw notReconcilable(facts, "the promotion '${promotion.name}' was taken off item ${promotion.orderItemId}, which is not on the bill")
            itemDeductions[line.vatId] = (itemDeductions[line.vatId] ?: BigDecimal.ZERO) + promotion.deductedAmount
        }
        val deducted = facts.promotions.fold(BigDecimal.ZERO) { acc, p -> acc + p.deductedAmount }
        if (deducted.compareTo(facts.promotionAmount) != 0) {
            throw notReconcilable(
                facts,
                "its promotions deducted ${deducted.plain()} in total, but its promotion amount is ${facts.promotionAmount.plain()}",
            )
        }
        return PromotionGroupApportionment.split(
            basis =
                PromotionGroupBasis(
                    chargedGross = linesByVat.mapValues { (_, lines) -> lines.sumGross() },
                    itemDeductions = itemDeductions,
                    billWideDeduction = facts.promotions.filter { it.orderItemId == null }.fold(BigDecimal.ZERO) { acc, p -> acc + p.deductedAmount },
                    rateOf = { vatId -> linesByVat.getValue(vatId).first().vatRateFraction },
                ),
            tieBreaker = Comparator.naturalOrder(),
        )
    }

    /**
     * No VAT key may come out below zero after its promotions. Bill creation could over-deduct an item
     * with two item-specific promotions and sign a negative row; an invoice must not carry that as a
     * negative taxable amount (BT-116).
     */
    private fun requireNoNegativeKey(
        facts: BillInvoiceFacts,
        split: PromotionGroupSplit<String>,
    ) {
        val rateByVat = facts.lines.associate { it.vatId to it.vatRateFraction }
        val negatives =
            split.postPromotionGross.filterValues { it.signum() < 0 }.toSortedMap().map { (vatId, gross) ->
                "at ${rateByVat.getValue(vatId).percent()} % its promotions deducted more than the items there, " +
                    "leaving a negative ${gross.plain()}"
            }
        if (negatives.isNotEmpty()) throw notReconcilable(facts, negatives.joinToString("; "))
    }

    /** Every VAT key: the meal row must be exactly its items after promotions. */
    private fun requireRowsMatch(
        facts: BillInvoiceFacts,
        mealRows: List<BillInvoiceVatFact>,
        split: PromotionGroupSplit<String>,
    ) {
        val rowKeys = mealRows.map { it.vatId }
        if (rowKeys.distinct().size != rowKeys.size) throw notReconcilable(facts, "it has two VAT detail rows for one VAT key")
        val rowByVat = mealRows.associateBy { it.vatId }
        val rateByVat = mealRows.associate { it.vatId to it.vatRateFraction } + facts.lines.associate { it.vatId to it.vatRateFraction }
        val mismatches =
            (rowByVat.keys + split.postPromotionGross.keys).sorted().mapNotNull { vatId ->
                val row = rowByVat[vatId]?.totalAmount ?: BigDecimal.ZERO
                val items = split.postPromotionGross[vatId] ?: BigDecimal.ZERO
                if (row.compareTo(items) == 0) return@mapNotNull null
                "at ${rateByVat.getValue(vatId).percent()} % the VAT detail is ${row.plain()}, but the items there less " +
                    "their promotions total ${items.plain()}"
            }
        if (mismatches.isNotEmpty()) throw notReconcilable(facts, mismatches.joinToString("; "))
    }

    private fun allowancesOf(
        facts: BillInvoiceFacts,
        mealRows: List<BillInvoiceVatFact>,
        split: PromotionGroupSplit<String>,
    ): List<DraftAllowance> {
        val linesByVat = facts.lines.groupBy { it.vatId }
        val rowByVat = mealRows.associateBy { it.vatId }
        return linesByVat.keys.flatMap { vatId ->
            val entries = entriesAt(vatId, facts, split, linesByVat)
            if (entries.isEmpty()) return@flatMap emptyList()
            val rate = linesByVat.getValue(vatId).first().vatRateFraction
            val itemsNet = netOf(linesByVat.getValue(vatId).sumGross(), rate)
            val keyNet = itemsNet - (rowByVat[vatId]?.netAmount ?: BigDecimal.ZERO)
            val nets = PromotionApportionment.apportion(keyNet, entries.indices.associateWith { entries[it].gross }, Comparator.naturalOrder())
            entries.mapIndexed { index, entry ->
                val net = nets.getValue(index)
                DraftAllowance(
                    amount = net,
                    vatAmount = entry.gross - net,
                    grossAmount = entry.gross,
                    vatRatePercent = rate.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP),
                    reason = entry.reason,
                    reasonCode = REASON_CODE_DISCOUNT,
                    billPromotionId = entry.billPromotionId,
                )
            }
        }
    }

    /** One allowance to be: an item-specific promotion, or all bill-wide promotions' share at [vatId]. */
    private data class Entry(
        val gross: BigDecimal,
        val reason: String,
        val billPromotionId: Long?,
    )

    private fun entriesAt(
        vatId: String,
        facts: BillInvoiceFacts,
        split: PromotionGroupSplit<String>,
        linesByVat: Map<String, List<BillInvoiceLineFact>>,
    ): List<Entry> {
        val itemsHere = linesByVat.getValue(vatId).map { it.orderItemId }.toSet()
        val itemSpecific =
            facts.promotions
                .filter { it.orderItemId != null && it.orderItemId in itemsHere && it.deductedAmount.signum() != 0 }
                .map { Entry(it.deductedAmount, "Aktion: ${it.name}", it.billPromotionId) }
        val billWideShare = split.billWideShares[vatId] ?: BigDecimal.ZERO
        if (billWideShare.signum() == 0) return itemSpecific
        // Only the promotions that actually deducted something are named.
        val billWide = facts.promotions.filter { it.orderItemId == null && it.deductedAmount.signum() != 0 }
        val reason = if (billWide.size == 1) "Aktion: ${billWide.single().name}" else "Aktionen: ${billWide.joinToString(", ") { it.name }}"
        return itemSpecific + Entry(billWideShare, reason, billWide.singleOrNull()?.billPromotionId)
    }

    /** The rows' own rule: `gross / (1 + rate)` HALF_UP at scale 2. */
    private fun netOf(
        gross: BigDecimal,
        rateFraction: BigDecimal,
    ): BigDecimal = gross.divide(BigDecimal.ONE + rateFraction, NET_SCALE, RoundingMode.HALF_UP)

    private fun List<BillInvoiceLineFact>.sumGross(): BigDecimal = fold(BigDecimal.ZERO) { acc, line -> acc + line.grossAmount }

    private fun BigDecimal.percent(): String = multiply(HUNDRED).stripTrailingZeros().toPlainString()

    private fun BigDecimal.plain(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun notReconcilable(
        facts: BillInvoiceFacts,
        reason: String,
    ) = PosException(
        PosErrorCode.SALES_INVOICE_BILL_NOT_RECONCILABLE,
        HttpStatusCode.Conflict,
        "Bill ${facts.billId} cannot be invoiced: its promotions cannot be shown as allowances that agree with its " +
            "VAT details exactly ($reason). Storno and bill again, then create the invoice.",
    )
}
