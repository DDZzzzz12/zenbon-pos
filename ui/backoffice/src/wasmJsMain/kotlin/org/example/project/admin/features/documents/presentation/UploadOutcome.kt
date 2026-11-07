package org.example.project.admin.features.documents.presentation

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.ProcessingStatus

/**
 * What became of an upload, in the terms an operator cares about.
 *
 * Narrower than [ProcessingStatus] on purpose. That enum carries the whole document workflow —
 * queue states that nothing writes, and the human-action states an operator sets later — while an
 * upload has only four endings worth telling someone about. Mapping once, here, keeps the screen
 * from branching on states an upload can never produce.
 *
 * [Duplicate] has no `ProcessingStatus` at all: the server refuses byte-identical content with a
 * 409 before any document exists. It belongs here because the operator's question is the same one —
 * "what happened to my file?" — and answering it with a raw validation error would leave them
 * wondering whether anything was filed.
 */
sealed interface UploadOutcome {
    /** A structured e-invoice was read and stored. [invoice] is what it says, for confirmation. */
    data class Extracted(
        val documentId: Long,
        val invoice: DocumentDto.InvoiceMetadataDto,
    ) : UploadOutcome

    /**
     * Filed, but nothing structured inside — the ordinary scanned-receipt case.
     *
     * Not an error and must never be styled as one. Most documents in a restaurant arrive on paper.
     */
    data class NoEInvoice(
        val documentId: Long,
    ) : UploadOutcome

    /**
     * A structured payload that is not a full EN 16931 invoice, or one the parser could not read.
     *
     * ZUGFeRD MINIMUM and BASIC WL land here by design: they are booking aids, and extracting them
     * as invoices would put figures on screen that the profile does not actually guarantee.
     */
    data class NotExtractable(
        val documentId: Long,
        val unreadable: Boolean,
    ) : UploadOutcome

    /** The archive already holds a byte-identical file. Nothing was stored a second time. */
    data object Duplicate : UploadOutcome

    companion object {
        /** Reads the upload response. Falls back to [NoEInvoice], which claims the least. */
        fun of(document: DocumentDto.DocumentResponseDto): UploadOutcome {
            val invoice = document.invoiceMetadata
            return when (document.processingStatus) {
                ProcessingStatus.COMPLETED ->
                    if (invoice != null) {
                        Extracted(document.id, invoice)
                    } else {
                        // COMPLETED without an invoice attached should not happen — the server only
                        // writes it once the invoice is stored. Reporting "read successfully" while
                        // showing nothing read would be the worse failure, so it degrades to the
                        // case that asks the operator to look.
                        NoEInvoice(document.id)
                    }
                ProcessingStatus.UNPROCESSABLE -> NotExtractable(document.id, unreadable = false)
                ProcessingStatus.FAILED -> NotExtractable(document.id, unreadable = true)
                else -> NoEInvoice(document.id)
            }
        }
    }
}
