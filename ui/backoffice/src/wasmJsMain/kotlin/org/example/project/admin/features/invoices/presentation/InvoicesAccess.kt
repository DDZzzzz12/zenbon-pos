package org.example.project.admin.features.invoices.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Which of the Rechnungen write actions the signed-in principal may be offered (FO-03).
 *
 * - [mayCapture] — the capture form moves the Beleg's processing status
 *   (`PUT /api/documents/{id}/status`, `POST /api/documents/{id}/invoice`: `DOCUMENT_WRITE`) and then
 *   writes the invoice (`PUT /api/invoices/{id}`, `/booking`: `INVOICE_WRITE`), so it needs both, or
 *   the save would fail half-way.
 * - [mayEditInvoice] — editing an invoice by its own id (no Beleg involved) only writes the invoice
 *   (`PUT /api/invoices/{id}`, `/booking`: `INVOICE_WRITE`), so it does not need `DOCUMENT_WRITE`.
 * - [mayMarkReviewed] — `PUT /api/invoices/{id}/review` is `INVOICE_WRITE`.
 */
data class InvoicesAccess(
    val mayCapture: Boolean,
    val mayEditInvoice: Boolean,
    val mayMarkReviewed: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): InvoicesAccess =
            InvoicesAccess(
                mayCapture = policy.allows(Capability.DOCUMENT_WRITE) && policy.allows(Capability.INVOICE_WRITE),
                mayEditInvoice = policy.allows(Capability.INVOICE_WRITE),
                mayMarkReviewed = policy.allows(Capability.INVOICE_WRITE),
            )
    }
}
