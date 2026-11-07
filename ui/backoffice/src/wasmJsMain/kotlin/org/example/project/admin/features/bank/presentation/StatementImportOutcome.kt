package org.example.project.admin.features.bank.presentation

import org.example.project.admin.features.documents.presentation.DocumentUploadState
import org.example.project.admin.features.documents.presentation.UploadOutcome
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.document.StatementImportNotice

/**
 * What became of a bank-statement import, in the terms of the *Bankkonten* screen.
 *
 * The shared upload section only ever said "Beleg angehängt: <file>", which read as success for a
 * statement the server had refused because its balances did not add up — the reason reached the
 * server log and nowhere else. This reads the upload response instead: the persisted
 * [ProcessingStatus] says whether a statement was stored, and the response-only
 * [StatementImportNotice]s say why not, or that it overlaps one already imported.
 *
 * Deliberately separate from [UploadOutcome], which is about e-invoices: `COMPLETED` with no invoice
 * attached is exactly what a successfully imported statement looks like, and that type degrades it
 * to "no e-invoice found".
 */
sealed interface StatementImportOutcome {
    /** Stored. [overlapsEarlier] when its period overlaps a statement already imported. */
    data class Imported(
        val overlapsEarlier: Boolean,
    ) : StatementImportOutcome

    /** Refused: opening balance plus bookings is not the closing balance. The file is archived. */
    data object NotReconciled : StatementImportOutcome

    /** Recognised as a statement but not readable. The file is archived. */
    data object Unreadable : StatementImportOutcome

    /** No camt.053 or MT940 inside; filed as an ordinary document. */
    data object NoStatement : StatementImportOutcome

    /** A byte-identical file is already archived; nothing was imported a second time. */
    data object Duplicate : StatementImportOutcome

    companion object {
        /** Null while nothing has been uploaded, or while an upload is still in flight. */
        fun of(state: DocumentUploadState): StatementImportOutcome? {
            if (state.isUploading) return null
            if (state.outcome == UploadOutcome.Duplicate) return Duplicate
            val document = state.uploaded ?: return null
            val notices = document.statementImportNotices
            // Before the status: a FAILED status on such a file is the *e-invoice* parser's verdict
            // (an XRechnung picked here by mistake), not an unreadable statement (E2E 18.8).
            if (StatementImportNotice.NOT_A_BANK_STATEMENT in notices) return NoStatement
            return when (document.processingStatus) {
                ProcessingStatus.COMPLETED ->
                    // An e-invoice picked here by mistake also completes — with an invoice behind it.
                    if (document.invoiceMetadata != null) {
                        NoStatement
                    } else {
                        Imported(overlapsEarlier = StatementImportNotice.OVERLAPS_EARLIER_STATEMENT in notices)
                    }
                ProcessingStatus.FAILED ->
                    if (StatementImportNotice.BALANCES_DO_NOT_RECONCILE in notices) NotReconciled else Unreadable
                else -> NoStatement
            }
        }
    }
}
