package org.example.project.admin.features.documents.presentation

import org.example.project.core.domain.enums.document.ProcessingStatus

/**
 * The Verlauf of an invoice document. Each step is ticked only when its fact is true:
 * - *Hochgeladen* is always reached.
 * - *Erfasst* only when an invoice is stored for the document ([hasInvoice]); a document that is merely
 *   `IN_PROGRESS`, or read nothing, has not been captured.
 * - *Geprüft* only when the document is `REVIEWED` (and an invoice exists).
 * - *Verknüpft* only when there is a real link ([realLinks] > 0, see [DocumentFacts]). Whether a payment was *allocated* is not on the document row, so it is never claimed.
 *
 * Exactly one entry is [DocumentHistoryEntry.current] unless everything is done.
 */
fun documentHistory(
    status: ProcessingStatus,
    hasInvoice: Boolean,
    realLinks: Int,
): List<DocumentHistoryEntry> {
    val reviewed = hasInvoice && status == ProcessingStatus.REVIEWED
    val linked = realLinks > 0
    return listOf(
        DocumentHistoryEntry(DocumentHistoryStep.UPLOADED, done = true, current = false),
        DocumentHistoryEntry(DocumentHistoryStep.CAPTURED, done = hasInvoice, current = !hasInvoice),
        DocumentHistoryEntry(DocumentHistoryStep.REVIEWED, done = reviewed, current = hasInvoice && !reviewed),
        DocumentHistoryEntry(DocumentHistoryStep.LINKED, done = linked, current = reviewed && !linked),
    )
}
