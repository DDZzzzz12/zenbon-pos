package org.example.project.einvoice.model

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * A parsed or to-be-generated e-invoice, independent of wire format and of this project's
 * persistence and DTO layers. The parser produces it; the generator consumes it.
 *
 * Totals are the document-level EN 16931 terms: BT-106 line net, BT-107 allowances, BT-108 charges,
 * BT-109 tax-exclusive, BT-110 tax, BT-112 tax-inclusive, BT-115 due.
 */
data class EInvoice(
    val format: EInvoiceFormat,
    val invoiceNumber: String,
    val issueDate: LocalDate,
    val currency: String,
    val seller: EInvoiceParty,
    /** BG-7 buyer party (BT-44 name, BT-48 VAT-ID, BT-50/51 address). Null on invoices with no buyer block. */
    val buyer: EInvoiceParty? = null,
    val vatBreakdown: List<EInvoiceVatBreakdown>,
    val lines: List<EInvoiceLine> = emptyList(),
    val allowancesCharges: List<EInvoiceAllowanceCharge> = emptyList(),
    val dueDate: LocalDate? = null,
    val deliveryDate: LocalDate? = null,
    val buyerReference: String? = null,
    val paymentIban: String? = null,
    val paymentReference: String? = null,
    val paymentTermsText: String? = null,
    /**
     * BT-3 Invoice type code, UNTDID 1001 — `380` commercial invoice, `381` credit note,
     * `384` corrected invoice.
     *
     * Nullable rather than defaulted to `380`, because "the sender stated 380" and "the sender
     * stated nothing" are different facts and only the persistence layer's column default may
     * collapse them. Nothing here validates the code against UNTDID 1001: the parser carries what
     * the sender wrote, and constraining the set is a capture-form decision.
     */
    val invoiceTypeCode: String? = null,
    /**
     * BT-25 Preceding invoice reference — the invoice a credit note or correction refers to.
     *
     * Only the document identifier is carried; BT-26 (the preceding invoice's issue date) is not
     * modelled because nothing in this project reads it.
     */
    val precedingInvoiceReference: String? = null,
    val lineNetTotal: BigDecimal? = null,
    /** BT-107 sum of document-level allowances ([allowancesCharges] with `isCharge = false`); null when none is stated. */
    val allowanceTotal: BigDecimal? = null,
    /** BT-108 sum of document-level charges; null when none is stated. */
    val chargeTotal: BigDecimal? = null,
    val taxExclusiveTotal: BigDecimal? = null,
    val taxTotal: BigDecimal? = null,
    val taxInclusiveTotal: BigDecimal? = null,
    val amountDue: BigDecimal? = null,
    /** BT-113 sum already paid — for "already paid, €0 due". BT-115 due = grand total − prepaid + rounding. */
    val prepaidAmount: BigDecimal? = null,
    /**
     * BT-114 rounding amount — added to BT-112 to reach the amount due (BR-CO-16:
     * BT-115 = BT-112 − BT-113 + BT-114). Null when the invoice states none, which EN 16931 reads as zero.
     */
    val roundingAmount: BigDecimal? = null,
)
