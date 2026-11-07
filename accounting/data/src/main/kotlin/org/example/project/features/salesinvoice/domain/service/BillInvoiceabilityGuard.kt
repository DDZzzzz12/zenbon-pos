package org.example.project.features.salesinvoice.domain.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.isStandingSale
import org.example.project.infrastructure.errors.PosException
import java.math.BigDecimal

/**
 * Whether a till bill may be turned into a §14 invoice, and whether the invoice agrees with it.
 *
 * The bill's self-consistency check is not pedantry: if `bill_amount` and the VAT details disagree,
 * the bill is already wrong, and issuing from it would launder that inconsistency into a legal
 * document that an auditor reads as our assertion.
 */
object BillInvoiceabilityGuard {
    /**
     * Refuses a bill that is not a standing sale — a Storno bill, a cancelled original or a
     * receivable clearing — on its own code, before anything else is looked at.
     *
     * Public so the create path can run it before the mapper, whose "no items" refusal would
     * otherwise answer first with a less precise reason.
     */
    fun requireStandingSale(
        billId: Long,
        kind: BillKind,
        isCancelled: Boolean,
    ) {
        if (isStandingSale(kind, isCancelled)) return
        throw PosException(
            PosErrorCode.BILL_NOT_A_STANDING_SALE,
            HttpStatusCode.Conflict,
            "Bill $billId cannot be invoiced: it is ${describe(kind)}, not a standing sale. " +
                "Only the original of a sale that was not cancelled can be invoiced.",
        )
    }

    fun requireInvoiceable(
        facts: BillInvoiceabilityFacts,
        invoiceGross: BigDecimal,
    ) {
        requireStandingSale(facts.billId, facts.kind, facts.isCancelled)
        if (facts.isTraining) {
            throw notInvoiceable(facts.billId, "the bill is a training bill and has no fiscal effect")
        }
        // The meal in the VAT details: an OWNER tip is taxable revenue the bill folded into its VAT
        // rows, and it is not part of the invoiced supply. An EMPLOYEE tip has no VAT row at all.
        // A promotion is already off the VAT details; the bill amount is the items before it.
        val mealTotal = facts.vatDetailTotal - facts.ownerTip
        if ((facts.billAmount - facts.promotionAmount).compareTo(mealTotal) != 0) {
            throw notInvoiceable(facts.billId, selfDisagreement(facts, mealTotal))
        }
        if (facts.hasLiveInvoice) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_BILL_ALREADY_INVOICED,
                HttpStatusCode.Conflict,
                "Bill ${facts.billId} already has a live invoice. Cancel it before issuing another.",
            )
        }
        if (facts.receivableTender > mealTotal) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_TIP_ON_ACCOUNT,
                HttpStatusCode.Conflict,
                "Bill ${facts.billId} cannot be invoiced: ${facts.receivableTender} was tendered on account, but the " +
                    "invoice covers only the meal of $mealTotal - the tip was paid on account. Rebook the tip to cash " +
                    "or card (Zahlartwechsel), then create the invoice.",
            )
        }
        if (invoiceGross.compareTo(mealTotal) != 0) {
            throw PosException(
                PosErrorCode.SALES_INVOICE_AMOUNT_MISMATCH,
                HttpStatusCode.Conflict,
                "The invoice totals $invoiceGross but bill ${facts.billId} totals $mealTotal" + ownerTipNote(facts),
            )
        }
    }

    /** Says which two figures disagree, and that an owner tip was already taken out of one of them. */
    private fun selfDisagreement(
        facts: BillInvoiceabilityFacts,
        mealTotal: BigDecimal,
    ): String {
        val items =
            if (facts.promotionAmount.signum() == 0) {
                "its item total ${facts.billAmount}"
            } else {
                "its item total ${facts.billAmount} less promotions ${facts.promotionAmount} " +
                    "(= ${facts.billAmount - facts.promotionAmount})"
            }
        return if (facts.ownerTip.signum() == 0) {
            "$items disagrees with its own VAT details ${facts.vatDetailTotal}"
        } else {
            "$items disagrees with its own VAT details ${facts.vatDetailTotal} less " +
                "the owner tip ${facts.ownerTip} (= $mealTotal)"
        }
    }

    private fun ownerTipNote(facts: BillInvoiceabilityFacts): String =
        if (facts.ownerTip.signum() == 0) {
            ""
        } else {
            " without its owner tip of ${facts.ownerTip}, which is never part of the invoice"
        }

    private fun describe(kind: BillKind): String =
        when (kind) {
            BillKind.STORNO -> "a Storno bill"
            BillKind.RECEIVABLE_CLEARING -> "a receivable clearing (a payment receipt)"
            BillKind.SALE, BillKind.REBOOKING -> "a cancelled original"
        }

    private fun notInvoiceable(
        billId: Long,
        reason: String,
    ) = PosException(
        PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE,
        HttpStatusCode.Conflict,
        "Bill $billId cannot be invoiced: $reason",
    )
}
