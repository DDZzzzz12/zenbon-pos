package org.example.project.features.document.domain.service

import org.example.project.core.domain.dto.document.DocumentDto

/**
 * Cancels an incoming invoice by a correction (Storno) instead of deleting its Beleg (T9-19).
 *
 * Nothing is removed: the original invoice and its document stay archived and visible, and a second
 * invoice row — the correction, the original's totals negated — neutralises it, so it no longer
 * counts as open in the supplier totals, the Rechnungen buckets, the Zuordnung suggestions or the
 * DATEV AP export. Throws `PosException`.
 */
interface InvoiceCancellationService {
    /**
     * Writes the correction for [invoiceId] and returns it.
     *
     * Refused with `INVOICE_NOT_CANCELLABLE` / 409 when the invoice is already cancelled, is itself a
     * correction, has no source document, or is allocated or marked paid. Audited as
     * `CANCEL_INCOMING_INVOICE` on success and on refusal alike.
     */
    suspend fun cancel(
        invoiceId: Long,
        request: DocumentDto.CancelInvoiceRequestDto,
        cancelledBy: String?,
    ): DocumentDto.InvoiceDto
}
