package org.example.project.features.supplier.data.service

import org.example.project.features.document.domain.models.Invoice
import org.example.project.features.supplier.domain.repository.SupplierRepository
import org.slf4j.LoggerFactory

/**
 * Derives creditors from the `seller_*` data on invoices that have none yet.
 *
 * One-shot and idempotent: an invoice that already carries a `supplier_id` is skipped, so this is
 * safe to run on every start. The resolution itself is [SupplierResolver]'s — this class owns only
 * the sweep, so a backfilled row and a freshly parsed one get identical treatment.
 *
 * Must run **after** `InvoiceBackfill`, whose output it consumes.
 *
 * Requires an ambient transaction.
 */
class SupplierBackfill(
    private val suppliers: SupplierRepository,
) {
    private val logger = LoggerFactory.getLogger(SupplierBackfill::class.java)
    private val resolver = SupplierResolver(suppliers)

    fun run(): SupplierBackfillReport {
        var linked = 0
        var skippedAlreadyLinked = 0
        var skippedNoSeller = 0
        val failed = mutableListOf<Long>()

        // Materialized up front: creating suppliers below flushes the entity cache, which must not
        // happen while a lazy SizedIterable over invoices is still being consumed.
        Invoice.all().toList().forEach { invoice ->
            try {
                when {
                    invoice.supplier != null -> skippedAlreadyLinked++
                    invoice.sellerName.isNullOrBlank() &&
                        invoice.sellerVatId.isNullOrBlank() &&
                        invoice.paymentIban.isNullOrBlank() -> skippedNoSeller++
                    else -> {
                        val outcome = resolver.resolveAndLink(invoice)
                        if (outcome.supplier != null) linked++ else skippedNoSeller++
                    }
                }
            } catch (e: Exception) {
                logger.warn("Supplier backfill failed for invoice {}", invoice.id.value, e)
                failed += invoice.id.value
            }
        }

        if (linked > 0 || failed.isNotEmpty()) {
            logger.info(
                "Supplier backfill: {} linked, {} already linked, {} without a seller, {} failed",
                linked,
                skippedAlreadyLinked,
                skippedNoSeller,
                failed.size,
            )
        }
        return SupplierBackfillReport(linked, skippedAlreadyLinked, skippedNoSeller, failed)
    }
}
