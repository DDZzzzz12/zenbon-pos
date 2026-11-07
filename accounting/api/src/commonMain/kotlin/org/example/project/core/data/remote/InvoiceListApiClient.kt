package org.example.project.core.data.remote

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.document.InvoiceListDto

/**
 * The Rechnungen list — a union of uncaptured documents and invoices. MANAGER-only server-side.
 */
interface InvoiceListApiClient {
    suspend fun list(
        bucket: InvoiceListDto.Bucket,
        limit: Int = 100,
        offset: Long = 0,
    ): InvoiceListDto.ListResponseDto

    /** One invoice by its own id — the only way to reach one that has no document. */
    suspend fun getInvoice(invoiceId: Long): DocumentDto.InvoiceDto

    /** Stamps confirmation, moving the invoice to *Fertig*. Idempotent server-side. */
    suspend fun markReviewed(invoiceId: Long): InvoiceListDto.RowDto
}
