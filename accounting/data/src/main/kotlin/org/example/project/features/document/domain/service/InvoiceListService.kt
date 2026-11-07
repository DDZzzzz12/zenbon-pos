package org.example.project.features.document.domain.service

import org.example.project.core.domain.dto.document.InvoiceListDto

/** The Rechnungen list — a union of uncaptured documents and invoices. Throws `PosException`. */
interface InvoiceListService {
    suspend fun list(
        bucket: InvoiceListDto.Bucket,
        limit: Int,
        offset: Long,
    ): InvoiceListDto.ListResponseDto

    /** Stamps `reviewed_at`, moving the invoice to *Fertig*. Idempotent: re-reviewing keeps the first stamp. */
    suspend fun markReviewed(invoiceId: Long): InvoiceListDto.RowDto
}
