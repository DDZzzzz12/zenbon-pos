package org.example.project.admin.features.documents.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Which of the Belege write actions the signed-in principal may be offered.
 *
 * Each flag names the capability `DocumentRules` / `InvoiceRules` require for the call behind the
 * control (FO-03), so a read-only group sees the archive without buttons the server would refuse:
 * - [mayWrite] — `POST /api/documents/upload` and `PUT /api/documents/{id}/bank-statement-metadata`
 *   are `DOCUMENT_WRITE`.
 * - [mayDelete] — `DELETE /api/documents/{id}` is the RESERVED `DOCUMENT_DELETE`, which a manager
 *   holds but an operator-created group may not.
 * - [mayCancelInvoice] — `POST /api/invoices/{id}/cancel` is `INVOICE_WRITE`.
 */
data class DocumentsAccess(
    val mayWrite: Boolean,
    val mayDelete: Boolean,
    val mayCancelInvoice: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): DocumentsAccess =
            DocumentsAccess(
                mayWrite = policy.allows(Capability.DOCUMENT_WRITE),
                mayDelete = policy.allows(Capability.DOCUMENT_DELETE),
                mayCancelInvoice = policy.allows(Capability.INVOICE_WRITE),
            )
    }
}
