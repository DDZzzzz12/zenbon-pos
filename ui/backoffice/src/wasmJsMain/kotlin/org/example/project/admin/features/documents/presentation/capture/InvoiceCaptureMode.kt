package org.example.project.admin.features.documents.presentation.capture

/**
 * Which write path a save takes — the single most important distinction on this screen.
 *
 * `GET /api/documents/{id}/invoice` returns **404 for a document with no invoice**, which is the
 * ordinary state of a freshly scanned PDF, not a failure. So the load treats
 * `RESOURCE_NOT_FOUND` as [Create] and everything else as a real error. Getting that backwards puts
 * an error dialog over the PDF of every document an operator opens to capture.
 *
 * [Edit] carries the **invoice** id, not the document id: `source_document_id` is deliberately
 * non-unique (one scanned PDF may carry several invoices), so the write endpoints are invoice-keyed.
 * It also carries [Edit.extractionSource], because that is what decides whether the supplier fields
 * are editable at all — `"EINVOICE_XML"` means the server will reject a supplier-field write with 409.
 */
internal sealed interface InvoiceCaptureMode {
    /** No invoice exists yet; a save POSTs to `/api/documents/{id}/invoice` and stamps `MANUAL`. */
    data object Create : InvoiceCaptureMode

    /** An invoice exists; a save PUTs to `/api/invoices/{invoiceId}` and/or its `/booking`. */
    data class Edit(
        val invoiceId: Long,
        val extractionSource: String,
    ) : InvoiceCaptureMode
}
