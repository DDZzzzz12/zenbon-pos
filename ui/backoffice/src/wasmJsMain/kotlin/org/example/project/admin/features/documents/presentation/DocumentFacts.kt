package org.example.project.admin.features.documents.presentation

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.document.ProcessingStatus

/**
 * What a document row can truthfully say about itself, for the Beleg-Verlauf, the Fortschritt squares and
 * the Verknüpft column.
 *
 * **`linkCount` on the wire is not "linked to a payment"** (it counts the document-to-document and
 * bank-statement link tables, including the document's own file link, plus a Kassenbuch link), so it is never
 * shown. The server's `allocationCount` is the payment fact: bank transactions allocated to the document's
 * invoice, reversals excluded. `hasInvoice` / `invoiceId` say whether a normalized invoice is stored.
 */
object DocumentFacts {
    /**
     * What "Verknüpft" counts. For an **invoice** document it is the payment fact, [allocations]: a Kassenbuch
     * or bank-statement document link does not say the invoice was paid. For any other type there is no
     * allocation to speak of, so the named links count (Kassenbuch entry, bank statements, linked documents),
     * plus [allocations] where the server reports some (the PDF of an outgoing invoice).
     */
    fun realLinkCount(
        fileType: String?,
        allocations: Int,
        kassenbuch: Int,
        bankStatements: Int,
        documents: Int,
    ): Int = if (fileType == DocumentType.INVOICE.name) allocations else allocations + kassenbuch + bankStatements + documents

    fun realLinkCount(item: DocumentDto.DocumentListItemDto): Int =
        realLinkCount(
            item.fileType,
            item.allocationCount,
            item.linkedKassenbuchIds.size,
            item.linkedBankStatementIds.size,
            item.linkedDocumentIds.size,
        )

    fun realLinkCount(document: DocumentDto.DocumentResponseDto): Int =
        realLinkCount(
            document.fileType,
            document.allocationCount,
            if (document.linkedKassenbuch != null) 1 else 0,
            document.linkedBankStatements.size,
            document.linkedDocuments.size,
        )

    /**
     * The entry card, from the same facts as the Verlauf: no stored invoice means the data is still to be
     * entered ([EntryCardMode.CAPTURE] — also after a failed or unprocessable read, so it can be keyed in by
     * hand); a stored invoice that is not yet reviewed is to be checked ([EntryCardMode.VERIFY]); a reviewed
     * one needs no entry. `processingStatus` alone is not trusted for "captured": an invoice captured through
     * the API can still be `NONE` or `IN_PROGRESS`.
     */
    fun entryCardMode(
        status: ProcessingStatus,
        hasInvoice: Boolean,
    ): EntryCardMode =
        when {
            !hasInvoice -> EntryCardMode.CAPTURE
            status == ProcessingStatus.REVIEWED -> EntryCardMode.NONE
            else -> EntryCardMode.VERIFY
        }

    /** A normalized invoice is stored for the document (the detail answer). */
    fun hasInvoice(document: DocumentDto.DocumentResponseDto): Boolean = document.invoiceId != null

    /** The server's `hasInvoice` on the list item: a normalized invoice row, not the legacy metadata fallback. */
    fun hasInvoice(item: DocumentDto.DocumentListItemDto): Boolean = item.hasInvoice
}
