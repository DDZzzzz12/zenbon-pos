package org.example.project.features.bill.domain.service

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.features.bill.domain.models.Bill

/**
 * The bill side's only view of the sales invoices that describe its bills. The accounting area
 * implements it, so the bill code reads an invoice without depending on the invoice tables.
 *
 * **Every function runs in the caller's transaction.**
 */
interface BillInvoiceLink {
    /** The bill's non-cancelled invoice (a cancelled one frees the bill's slot), or null. */
    fun liveInvoiceFor(billId: Long): LiveBillInvoice?

    /** Removes the bill's `DRAFT` invoice, if it has one; an `ISSUED` invoice is left alone. */
    fun deleteDraftInvoiceFor(billId: Long)

    /** The bill behind each of [invoiceIds], keyed by invoice id; invoices not issued from a bill are absent. */
    fun billLinksForInvoices(invoiceIds: Collection<Long>): Map<Long, BillDto.BillLinkDto>

    /**
     * The ORIGINAL bill behind a receivable-clearing [bill]: the bill the invoice it settles
     * ([Bill.clearsSalesInvoiceId]) was issued from — the Vorgang that opened the receivable.
     *
     * Null when [bill] clears nothing, or when that invoice was not issued from a bill.
     *
     * @throws IllegalStateException when [Bill.clearsSalesInvoiceId] names no invoice row. The
     *   cross-area foreign key `fk_bill_clears_sales_invoice_id__id` (`ON DELETE RESTRICT`) makes that
     *   unreachable; failing keeps the DSFinV-K export from silently dropping a reference.
     */
    fun originalBillIdOfClearing(bill: Bill): Long?
}
