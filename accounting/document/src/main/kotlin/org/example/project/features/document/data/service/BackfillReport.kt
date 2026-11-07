package org.example.project.features.document.data.service

/**
 * Outcome of one [InvoiceBackfill] pass.
 *
 * [skippedExisting] is the steady state: after the first successful pass every legacy row already
 * has an invoice, so every later start reports `migrated = 0` and the full row count here.
 *
 * [failed] holds the *document* ids of rows that could not be mapped. Nothing is lost when a row
 * lands here — this sub-project leaves `document_invoice_metadata` intact, so the row is still
 * there to retry.
 */
data class BackfillReport(
    val migrated: Int,
    val skippedExisting: Int,
    val failed: List<Long>,
)
