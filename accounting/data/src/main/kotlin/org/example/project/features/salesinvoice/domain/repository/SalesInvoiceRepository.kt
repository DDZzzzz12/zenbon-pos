package org.example.project.features.salesinvoice.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.salesinvoice.domain.service.StandaloneInvoiceContent
import org.example.project.features.setup.domain.models.ShopInfo
import java.math.BigDecimal
import java.util.UUID

/**
 * The **only** writer of `sales_invoice`.
 *
 * Exposed DAO entities expose `var` properties, so "immutable once issued" cannot be enforced by the
 * type system. It is enforced here instead: every mutator re-reads the status and throws
 * `SALES_INVOICE_IMMUTABLE` rather than trusting its caller. A guard that lives only in the service
 * is one the next caller bypasses.
 *
 * **Every method requires an ambient transaction.**
 */
interface SalesInvoiceRepository {
    fun createDraft(
        shop: ShopInfo,
        revenueOrigin: RevenueOrigin,
        buyerName: String,
        issueDate: LocalDateTime,
        serviceDate: LocalDateTime,
        billId: Long? = null,
    ): SalesInvoice

    fun findById(id: Long): SalesInvoice?

    /** Refused unless the invoice is still a `DRAFT`. */
    fun updateDraft(
        invoice: SalesInvoice,
        buyerName: String? = null,
        buyerVatId: String? = null,
        buyerStreet: String? = null,
        buyerZip: String? = null,
        buyerCity: String? = null,
        notes: String? = null,
    )

    /** Refused unless the invoice is still a `DRAFT`. */
    fun deleteDraft(invoice: SalesInvoice)

    /**
     * Replaces the **entire content** of a draft — lines, VAT breakdown, header money fields, dates,
     * terms and notes — in one transaction. Refused unless the invoice is still a `DRAFT`.
     *
     * A full replace, not a merge: whatever the draft held is deleted and rewritten from [content],
     * so an edit that removes a line cannot leave the old line's money in the totals. The caller
     * (the service) derives [content] from the request via `StandaloneInvoiceMapper`, so no
     * client-supplied total ever reaches the row.
     */
    fun replaceDraftContent(
        invoice: SalesInvoice,
        content: StandaloneInvoiceContent,
    )

    /** Refused unless the invoice is still a `DRAFT`. Consumes nothing itself — the caller supplies the number. */
    fun markIssued(
        invoice: SalesInvoice,
        invoiceNumber: String,
        issuedBy: UUID?,
    )

    /** Refused unless the invoice is `ISSUED`. */
    fun markCancelled(
        invoice: SalesInvoice,
        cancelledBy: UUID?,
        reason: String?,
    )

    /** Allowed in every state: allocations maintain the residual, and a payment does not alter the document. */
    fun updateOpenAmount(
        invoice: SalesInvoice,
        openAmount: BigDecimal,
    )

    /**
     * A page of invoices, newest first, with the total matching the same filter.
     *
     * Ordered `issue_date DESC, id DESC`: without the id tiebreak two invoices issued in the same
     * second order arbitrarily and the list flickers between reloads.
     *
     * [open] narrows to rows whose coalesced `open_amount` is positive (`open_amount > 0`, else
     * `amount_due > 0`, else `tax_inclusive_total > 0`); [overdue] additionally requires that
     * the due day is over (strictly before today). **`overdue` implies `open`** — only an issued row with a positive residual
     * can be late, so asking for overdue rows without the open flag still narrows to the open
     * bucket rather than returning the whole table. Only meaningful for `ISSUED` invoices — drafts
     * and cancellations never qualify.
     *
     * [debtorId] narrows to the invoices issued to one customer. An invoice with no debtor — a
     * walk-in sale — is never returned by a debtor filter, and a null [debtorId] is not a filter at
     * all. It belongs here rather than at the caller for the same reason the other filters do: the
     * result is paged newest-first, so filtering a fetched page would report a debtor whose last
     * invoice fell outside it as having none.
     */
    fun list(
        status: SalesInvoiceStatus?,
        invoiceTypeCode: String?,
        open: Boolean,
        overdue: Boolean,
        debtorId: Long? = null,
        limit: Int,
        offset: Long,
    ): Pair<List<SalesInvoice>, Long>

    /**
     * Whether [billId] already has an invoice that is not `CANCELLED`.
     *
     * [excludingInvoiceId] drops one row from the count. The issue-time re-assertion of the bill
     * guard needs this: at issue the invoice being issued is itself a non-cancelled row, and without
     * the exclusion the guard would refuse every bill-derived issuance as "already invoiced".
     */
    fun hasLiveInvoiceForBill(
        billId: Long,
        excludingInvoiceId: Long? = null,
    ): Boolean
}
