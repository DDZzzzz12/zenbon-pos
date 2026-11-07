package org.example.project.features.salesinvoice.domain.service

import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import java.util.UUID

/** The outgoing-invoice lifecycle. Every method opens its own transaction. */
interface SalesInvoiceService {
    /**
     * [createFromBill], read back as a DTO.
     *
     * The DTO-returning wrappers exist because a route must not touch an Exposed entity outside a
     * transaction — the line and VAT collections are lazy — and because the lifecycle methods are
     * exercised directly by the slice's own tests, which should not be forced through the wire shape.
     */
    suspend fun createFromBillAsDto(
        request: SalesInvoiceDto.CreateFromBillRequestDto,
        createdBy: UUID?,
    ): SalesInvoiceDto.ResponseDto

    /** [issue], read back as a DTO. */
    suspend fun issueAsDto(
        invoiceId: Long,
        issuedBy: UUID,
    ): SalesInvoiceDto.ResponseDto

    /** [cancel], read back as a DTO. Returns the **Gutschrift**, not the original. */
    suspend fun cancelAsDto(
        invoiceId: Long,
        cancelledBy: UUID,
        reason: String?,
    ): SalesInvoiceDto.ResponseDto

    /**
     * A page of invoices for the Ausgangsrechnungen screen.
     *
     * [status] and [invoiceTypeCode] are the bucket filters: DRAFT for Entwürfe, ISSUED + `380` for
     * Ausgestellt, `381` for Gutschriften, CANCELLED for Storniert. [open] narrows to issued rows
     * with a positive open amount (Offen), [overdue] adds that the due day is over, strictly before today (Überfällig).
     */
    suspend fun listAsDto(
        status: String?,
        invoiceTypeCode: String?,
        open: Boolean,
        overdue: Boolean,
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): SalesInvoiceDto.ListResponseDto

    /** One invoice, or a 404 `PosException` when it does not exist. */
    suspend fun getAsDto(invoiceId: Long): SalesInvoiceDto.ResponseDto

    /**
     * Creates a `DRAFT` invoice for an already-settled till bill.
     *
     * The bill's fiscal content is snapshotted, not referenced: totals and VAT come from the bill's
     * own `bill_vat_detail` rows, the item names and prices are copied, and the TSE signature block
     * is carried onto the invoice per §6 KassenSichV.
     *
     * Refuses a bill that is deleted, a training bill, inconsistent with its own VAT details, or
     * already carrying a live invoice.
     */
    suspend fun createFromBill(
        billId: Long,
        buyer: InvoiceBuyer,
        createdBy: UUID?,
    ): SalesInvoice

    /**
     * Creates a case-C (STANDALONE) draft from human-typed lines.
     *
     * The seller block is snapshotted from `shop_info` at creation; the lines are split
     * gross→net/VAT and the BG-23 breakdown is summed server-side. No number is consumed — only
     * `issue()` does that.
     */
    suspend fun createStandaloneAsDto(
        request: SalesInvoiceDto.CreateStandaloneRequestDto,
        createdBy: UUID?,
    ): SalesInvoiceDto.ResponseDto

    /** Full-replaces a draft's content. Refused with `SALES_INVOICE_IMMUTABLE` once the invoice is ISSUED. */
    suspend fun updateStandaloneAsDto(
        invoiceId: Long,
        request: SalesInvoiceDto.UpdateStandaloneRequestDto,
    ): SalesInvoiceDto.ResponseDto

    /** Removes a draft. Refused with `SALES_INVOICE_IMMUTABLE` once the invoice is ISSUED. */
    suspend fun deleteStandalone(invoiceId: Long)

    /**
     * Validates, consumes a number, freezes the invoice and archives its PDF — in that order,
     * which is the point: a rejected issuance leaves the sequence untouched.
     *
     * [issuedBy] is **not nullable**: issuing is an act by a person, §14 makes it consequential, and
     * the archived document records who performed it.
     */
    suspend fun issue(
        invoiceId: Long,
        issuedBy: UUID,
    ): SalesInvoice

    /**
     * Cancels an issued invoice by creating a **381 Gutschrift** with its own number and negated
     * amounts, and marking the original `CANCELLED`.
     *
     * Not a status flip: the Gutschrift is itself a §14 document that the recipient and the
     * Steuerberater both need. "It was never sent, just delete it" is what produces a gap.
     */

    /**
     * The archived bytes of an issued invoice.
     *
     * **Never re-renders.** A template or logo change must not alter what a 2027 invoice looks like,
     * or the customer's copy stops matching the one an auditor is shown.
     */
    suspend fun reprint(invoiceId: Long): ByteArray

    suspend fun cancel(
        invoiceId: Long,
        cancelledBy: UUID,
        reason: String?,
    ): SalesInvoice

    /**
     * Takes a payment at the counter against an open `POS_RECEIVABLE` invoice.
     *
     * One transaction: a TSE-signed clearing [org.example.project.features.bill.domain.models.Bill],
     * its payment row, and the `TILL_RECEIPT` allocation that drops `open_amount`. A failure anywhere
     * leaves none of the three.
     */
    suspend fun settleAtTill(
        invoiceId: Long,
        request: SalesInvoiceDto.SettleAtTillRequestDto,
        employeeId: UUID,
        deviceId: String,
    ): SalesInvoiceDto.ResponseDto
}
