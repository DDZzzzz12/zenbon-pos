package org.example.project.features.document.data.service

import org.example.project.features.document.domain.models.DocumentInvoiceMetadata
import org.example.project.features.document.domain.repository.DocumentRepository
import org.slf4j.LoggerFactory

/**
 * Copies legacy `document_invoice_metadata` rows into the normalized invoice model. One-shot and
 * idempotent: a row whose document already has an invoice is skipped, so this is safe to run on
 * every start.
 *
 * The mapping itself lives in [LegacyInvoiceSync] and is shared with the live write path — this
 * class only owns the sweep and the skip-if-exists decision. Skipping is deliberate and specific
 * to startup: it keeps a boot cheap and stops the backfill rewriting rows it wrote on a previous
 * boot. The live path instead uses [LegacyInvoiceSync.syncFromLegacy], which overwrites, because
 * there a correction has to reach the export.
 *
 * Requires an ambient transaction — it reads and writes entities directly, like the repository it
 * calls. A failure is contained to that transaction, so server startup still proceeds, but it is
 * **all-or-nothing**: no partial progress is retained. The per-row catch below records ids in
 * [BackfillReport.failed] on a best-effort basis only — Exposed flushes a pending `Invoice.new` at
 * the next query, so a failing row usually surfaces inside the *next* row's `try` and is attributed
 * to the wrong document id.
 */
class InvoiceBackfill(
    private val repository: DocumentRepository,
) {
    private val logger = LoggerFactory.getLogger(InvoiceBackfill::class.java)
    private val sync = LegacyInvoiceSync(repository)

    fun run(): BackfillReport {
        var migrated = 0
        var skippedExisting = 0
        val failed = mutableListOf<Long>()

        // Materialized up front: creating invoices below flushes the entity cache, which must not
        // happen while a lazy SizedIterable over the same table is still being consumed.
        DocumentInvoiceMetadata.all().toList().forEach { legacy ->
            val documentId = legacy.document.id.value
            try {
                if (repository.getInvoiceBySourceDocument(documentId) != null) {
                    skippedExisting++
                } else {
                    sync.createFromLegacy(legacy)
                    migrated++
                }
            } catch (e: Exception) {
                logger.warn("Invoice backfill could not map document $documentId — leaving the legacy row in place", e)
                failed += documentId
            }
        }

        val report = BackfillReport(migrated, skippedExisting, failed)
        if (report.failed.isEmpty()) {
            logger.info(
                "Invoice backfill finished: migrated={}, skippedExisting={}, failed=0",
                report.migrated,
                report.skippedExisting,
            )
        } else {
            // ERROR, not INFO: the sweep is all-or-nothing in one transaction and document search
            // now reads the normalized model, so a non-empty `failed` means every pre-existing
            // document is missing from the vendor/invoice-number/amount filters, not just from the
            // DATEV export. Ids are best-effort — see the class KDoc on misattribution.
            logger.error(
                "Invoice backfill finished with {} unmapped legacy row(s) (document ids, best-effort: {}) — " +
                    "the DATEV Eingangsrechnungen export is stale and document search by vendor name, invoice " +
                    "number or amount will not find pre-existing documents until this succeeds. " +
                    "migrated={}, skippedExisting={}",
                report.failed.size,
                report.failed,
                report.migrated,
                report.skippedExisting,
            )
        }
        return report
    }
}
