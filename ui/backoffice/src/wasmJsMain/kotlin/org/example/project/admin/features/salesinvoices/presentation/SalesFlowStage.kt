package org.example.project.admin.features.salesinvoices.presentation

/**
 * The three stages of the outgoing Belegfluss: Entwurf · Ausgestellt · Offen.
 *
 * A "Bezahlt" stage would need a bucket the server does not have, so paid invoices simply leave
 * *Offen*. [bucket] is the bucket a stage opens; the sub-filters of *Ausgestellt* (Überfällig,
 * Storniert, Gutschriften) are other buckets that belong to the same stage.
 */
enum class SalesFlowStage(
    val bucket: SalesInvoiceBucket,
) {
    DRAFT(SalesInvoiceBucket.DRAFTS),
    ISSUED(SalesInvoiceBucket.ISSUED),
    OPEN(SalesInvoiceBucket.OPEN),
}
