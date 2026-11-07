package org.example.project.core.data.remote

import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto

/**
 * Outgoing invoices (Ausgangsrechnungen).
 *
 * Every method is MANAGER-gated server-side; a non-manager caller earns a 403 rather than an empty
 * result, so the UI hides these actions rather than letting them fail.
 */
interface SalesInvoiceApiClient {
    /**
     * A page of invoices for the Ausgangsrechnungen buckets.
     *
     * @param status `DRAFT` | `ISSUED` | `CANCELLED`, or null for all.
     * @param type UNTDID 1001 — `380` invoices, `381` Gutschriften — or null for all.
     * @param open narrows to issued rows with a positive open amount (Offen).
     * @param overdue adds that the due day is over, strictly before today (Überfällig).
     * @param debtorId narrows to one customer's invoices. A walk-in sale carries no debtor and is
     *   never returned by this filter; null is not a filter.
     */
    suspend fun list(
        status: String? = null,
        type: String? = null,
        open: Boolean = false,
        overdue: Boolean = false,
        debtorId: Long? = null,
        limit: Int = 50,
        offset: Long = 0,
    ): SalesInvoiceDto.ListResponseDto

    suspend fun get(id: Long): SalesInvoiceDto.ResponseDto

    /** Creates a DRAFT from a settled till bill; the server snapshots its lines, VAT and TSE block. */
    suspend fun createFromBill(request: SalesInvoiceDto.CreateFromBillRequestDto): SalesInvoiceDto.ResponseDto

    /** Creates a case-C draft from human-typed lines. The server derives net/VAT and sums the breakdown. */
    suspend fun createStandalone(request: SalesInvoiceDto.CreateStandaloneRequestDto): SalesInvoiceDto.ResponseDto

    /** Full-replaces a draft's content. 409 `SALES_INVOICE_IMMUTABLE` once the invoice is ISSUED. */
    suspend fun updateStandalone(
        id: Long,
        request: SalesInvoiceDto.UpdateStandaloneRequestDto,
    ): SalesInvoiceDto.ResponseDto

    /** Deletes a draft. 409 once ISSUED — issuing a Gutschrift is the only way out of ISSUED. */
    suspend fun deleteStandalone(id: Long)

    /** Consumes a §14 number and archives the PDF. Irreversible — the only way out is [cancel]. */
    suspend fun issue(id: Long): SalesInvoiceDto.ResponseDto

    /** Settles an open receivable at the counter; returns the invoice with its reduced open amount. */
    suspend fun settleAtTill(
        id: Long,
        request: SalesInvoiceDto.SettleAtTillRequestDto,
    ): SalesInvoiceDto.ResponseDto

    /** Returns the **381 Gutschrift**, which carries its own number — not the cancelled original. */
    suspend fun cancel(
        id: Long,
        request: SalesInvoiceDto.CancelRequestDto,
    ): SalesInvoiceDto.ResponseDto

    /** The archived PDF bytes. Served from storage; never re-rendered. */
    suspend fun pdf(id: Long): ByteArray

    /** Preflight for the DATEV Debitoren export: counts, total, and any invoices that would block it. */
    suspend fun datevExportStatistics(
        start: String,
        end: String,
    ): DatevArExportDto.ArExportStatisticsDto

    /** The DATEV Debitoren (accountsReceivableLedger) ZIP for issued STANDALONE invoices in `[start, end]`. */
    suspend fun datevExport(
        start: String,
        end: String,
    ): ByteArray
}
