package org.example.project.features.salesinvoice.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import java.math.BigDecimal

/**
 * Everything the invoice PDF shows, taken **only** from the invoice's own snapshot.
 *
 * Deliberately not the bill: an issued invoice must be reproducible from itself two years later,
 * when the products have been renamed and the shop has moved. Every field here comes from
 * `sales_invoice` or its children.
 */
data class SalesInvoicePdfData(
    val invoiceNumber: String,
    val issueDate: LocalDateTime,
    val serviceDate: LocalDateTime,
    val invoiceTypeCode: String,
    val precedingInvoiceNumber: String?,
    val buyer: InvoiceBuyer,
    val seller: InvoiceSeller,
    val lines: List<SalesInvoicePdfLine>,
    val vatRows: List<SalesInvoicePdfVatRow>,
    /** BT-107, BT-108 — printed as a "Nachlass" block below the lines; the net is BT-109, after them. */
    val allowances: List<SalesInvoicePdfAllowance> = emptyList(),
    val netTotal: BigDecimal,
    val vatTotal: BigDecimal,
    val grossTotal: BigDecimal,
    val prepaidAmount: BigDecimal,
    val amountDue: BigDecimal,
    val paymentNote: String?,
    /** The Verwendungszweck — the invoice number, printed on a STANDALONE invoice. */
    val paymentReference: String?,
    /** The Zahlungsziel — when the STANDALONE invoice must be settled. */
    val dueDate: LocalDateTime?,
    val paymentTermsText: String?,
    /**
     * Whether this invoice documents a till (KassenSichV) transaction and therefore *expects* a TSE
     * signature. True for a from-bill invoice (`POS_SETTLED`/`POS_RECEIVABLE`), false for a STANDALONE
     * invoice on account. It decides whether an absent [tse] is disclosed as an Ausfall or omitted:
     * a standalone invoice never went through the till, so "TSE nicht verfügbar" would assert a
     * failure that never applied.
     */
    val expectsTse: Boolean,
    /**
     * The originating bill's readable TSE data (AEAO Nr. 2.4.4) — the same values its receipt
     * printed. Null when the bill went unsigned, was signed in SIMULATION mode, or for a STANDALONE
     * invoice; [expectsTse] then decides between the Ausfall disclosure and no block at all.
     */
    val tse: TseReceiptDataDto?,
)
