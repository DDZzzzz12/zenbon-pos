package org.example.project.features.salesinvoice.domain.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Builds the content of an invoice for an already-settled till bill.
 *
 * Pure: it takes facts and returns content, so every rule below is testable without a database.
 *
 * Two rules carry the whole design:
 *
 * 1. **The VAT breakdown is copied**, never recomputed — those rows are the fiscal split the receipt
 *    printed and the Z-Bon aggregated. The one exception is an OWNER tip, which the bill folded into
 *    its rows as taxable revenue: the invoice covers the meal and never the tip, so [OwnerTipRemoval]
 *    takes exactly the share the bill added back out of each row it went into. A promotion (Aktion)
 *    is not a line: the lines stay at the price charged and each promotion becomes an EN 16931
 *    document-level allowance (BG-20) whose VAT the rows already absorbed — see
 *    [BillInvoiceReconciliation].
 * 2. **Line VAT is derived by subtraction** — `net = gross / (1 + rate)` rounded, `vat = gross - net`
 *    — so `net + vat == gross` holds by construction rather than by luck.
 *
 * And the two are reconciled: if the lines do not sum to the fiscal total, the mapper refuses. An
 * invoice that disagrees with its own Kassenbeleg is the defect this whole slice exists to prevent,
 * and a balancing line would hide it.
 */
object BillToInvoiceMapper {
    /** Money keeps the DB's scale; rates are percent units at scale 2. */
    private const val MONEY_SCALE = 4
    private val HUNDRED = BigDecimal("100")

    fun toDraftContent(
        facts: BillInvoiceFacts,
        buyer: InvoiceBuyer,
    ): DraftInvoiceContent {
        if (facts.lines.isEmpty()) {
            throw notInvoiceable(facts.billId, "it has no items to invoice")
        }
        if (facts.vatRows.isEmpty()) {
            throw notInvoiceable(facts.billId, "it has no VAT details, so its fiscal split is unknown")
        }

        val reconciled = BillInvoiceReconciliation.reconcile(facts)
        val mealRows = reconciled.mealRows
        val allowances = reconciled.allowances
        val vatRows = mealRows.map { it.toDraftRow() }
        val lines = facts.lines.mapIndexed { index, item -> item.toDraftLine(index + 1) }

        val fiscalTotal = mealRows.fold(BigDecimal.ZERO) { acc, row -> acc + row.totalAmount }
        val lineGrossTotal = lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.grossAmount }
        val allowanceGrossTotal = allowances.fold(BigDecimal.ZERO) { acc, allowance -> acc + allowance.grossAmount }
        if ((lineGrossTotal - allowanceGrossTotal).compareTo(fiscalTotal) != 0) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_AMOUNT_MISMATCH,
                HttpStatusCode.Conflict,
                "Bill ${facts.billId}: its items sum to $lineGrossTotal" + promotionNote(allowanceGrossTotal) +
                    " but its VAT details total $fiscalTotal. An invoice must agree with the Kassenbeleg exactly.",
            )
        }
        val taxExclusiveTotal = mealRows.fold(BigDecimal.ZERO) { acc, row -> acc + row.netAmount }
        val allowanceTotal = allowances.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { acc, allowance -> acc + allowance.amount }

        return DraftInvoiceContent(
            billId = facts.billId,
            billNumber = facts.billNumber,
            buyer = buyer,
            seller = facts.seller,
            serviceDate = facts.paidAt,
            lines = lines,
            vatRows = vatRows,
            allowances = allowances,
            lineNetTotal = taxExclusiveTotal + (allowanceTotal ?: BigDecimal.ZERO),
            allowanceTotal = allowanceTotal,
            taxExclusiveTotal = taxExclusiveTotal,
            taxTotal = mealRows.fold(BigDecimal.ZERO) { acc, row -> acc + row.vatAmount },
            taxInclusiveTotal = fiscalTotal,
            tse = facts.tse,
            paymentNote =
                paymentNote(
                    paidAt = facts.paidAt,
                    billNumber = facts.billNumber,
                    split = ReceivableSettlement.split(fiscalTotal, facts.receivableTender),
                ),
        )
    }

    private fun promotionNote(allowanceGross: BigDecimal): String = if (allowanceGross.signum() == 0) "" else " less promotions of $allowanceGross"

    /** Copied verbatim (after any owner-tip removal); only the rate changes units. */
    private fun BillInvoiceVatFact.toDraftRow() =
        DraftVatRow(
            vatRatePercent = vatRateFraction.toPercentUnits(),
            taxableAmount = netAmount,
            vatAmount = vatAmount,
        )

    /** VAT by subtraction, so `net + vat == gross` cannot drift. */
    private fun BillInvoiceLineFact.toDraftLine(lineNo: Int): DraftLine {
        val net = grossAmount.divide(BigDecimal.ONE.add(vatRateFraction), MONEY_SCALE, RoundingMode.HALF_UP)
        return DraftLine(
            lineNo = lineNo,
            itemName = itemName,
            quantity = BigDecimal.ONE,
            netUnitPrice = net,
            netAmount = net,
            vatAmount = grossAmount.subtract(net),
            grossAmount = grossAmount,
            vatRatePercent = vatRateFraction.toPercentUnits(),
            sourceOrderItemId = orderItemId,
        )
    }

    /** `VatTable.vatRate` is a fraction (0.19); EN 16931 BT-119 is percent units (19.00). */
    private fun BigDecimal.toPercentUnits(): BigDecimal = multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)

    /**
     * The §14-relevant sentence that ties the invoice to the receipt. An auditor reads this to find
     * the Z-Bon; the reverse direction is a query on `sales_invoice.bill_id`.
     *
     * It acknowledges receipt **only of what was paid**: a bill tendered "auf Rechnung" leaves
     * [ReceivableSettlement.Split.due] open, and "Betrag dankend erhalten" on such an invoice tells
     * the customer they owe nothing. The service re-runs this at issue with the final split, derived
     * from the bill again at that point.
     */
    fun paymentNote(
        paidAt: LocalDateTime,
        billNumber: String,
        split: ReceivableSettlement.Split,
    ): String {
        val date = paidAt.date
        val day = date.dayOfMonth.toString().padStart(2, '0')
        val month = date.monthNumber.toString().padStart(2, '0')
        val on = "$day.$month.${date.year}"
        return when {
            split.due.signum() <= 0 -> "Betrag dankend erhalten am $on, Beleg $billNumber"
            split.prepaid.signum() <= 0 ->
                "Leistung gemäß Beleg $billNumber vom $on. Der Rechnungsbetrag von ${split.due.euro()} " +
                    "ist offen und zur Zahlung fällig."
            else ->
                "Teilbetrag von ${split.prepaid.euro()} dankend erhalten am $on, Beleg $billNumber. " +
                    "Der offene Betrag von ${split.due.euro()} ist zur Zahlung fällig."
        }
    }

    /** German money form straight from `BigDecimal` — never through a `Double`. */
    private fun BigDecimal.euro(): String = setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " €"

    private fun notInvoiceable(
        billId: Long,
        reason: String,
    ) = PosException(
        PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE,
        HttpStatusCode.Conflict,
        "Bill $billId cannot be invoiced: $reason",
    )
}
