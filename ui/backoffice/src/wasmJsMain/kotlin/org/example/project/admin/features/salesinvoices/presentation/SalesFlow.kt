package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.money.Decimal

/** What the outgoing "Nächster Schritt" bar proposes: drafts to issue, then payments to assign. */
data class SalesNextStep(
    val drafts: Long,
    val open: Long,
)

/**
 * The pure rules of the outgoing Belegfluss, kept free of Compose so each one is pinned by a test.
 */
object SalesFlow {
    /** The sub-filters of the Ausgestellt stage, in chip order (*Alle ausgestellt* is the stage's own bucket). */
    val ISSUED_BUCKETS =
        listOf(
            SalesInvoiceBucket.ISSUED,
            SalesInvoiceBucket.OVERDUE,
            SalesInvoiceBucket.CANCELLED,
            SalesInvoiceBucket.CREDIT_NOTES,
        )

    fun stageOf(bucket: SalesInvoiceBucket): SalesFlowStage =
        when (bucket) {
            SalesInvoiceBucket.DRAFTS -> SalesFlowStage.DRAFT
            SalesInvoiceBucket.OPEN -> SalesFlowStage.OPEN
            SalesInvoiceBucket.ISSUED,
            SalesInvoiceBucket.OVERDUE,
            SalesInvoiceBucket.CREDIT_NOTES,
            SalesInvoiceBucket.CANCELLED,
            -> SalesFlowStage.ISSUED
        }

    /**
     * The proposal for the bar: drafts to issue first, then open invoices to settle. Null when the
     * counts are not loaded yet or neither is waiting.
     */
    fun nextStep(counts: Map<SalesInvoiceBucket, Long>): SalesNextStep? {
        if (counts.isEmpty()) return null
        val drafts = counts[SalesInvoiceBucket.DRAFTS] ?: 0L
        val open = counts[SalesInvoiceBucket.OPEN] ?: 0L
        return if (drafts > 0L || open > 0L) SalesNextStep(drafts, open) else null
    }

    /**
     * A row's status word. Cancelled, Gutschrift and Entwurf come from the document itself. An issued
     * invoice with a positive open amount is *Offen* — *Überfällig* when the list being shown is the
     * overdue bucket, the only place the row list knows it (a row carries no due date) — and with an
     * open amount of zero it is *Erledigt*. An issued invoice with no open amount at all (receivables
     * were not tracked) stays plain *Ausgestellt* rather than claiming to be settled.
     */
    fun statusOf(
        row: SalesInvoiceDto.ListItemDto,
        shownBucket: SalesInvoiceBucket,
    ): SalesRowStatus {
        if (row.status == "CANCELLED") return SalesRowStatus.CANCELLED
        if (row.invoiceTypeCode == CREDIT_NOTE_TYPE_CODE) return SalesRowStatus.CREDIT_NOTE
        if (row.status == "DRAFT") return SalesRowStatus.DRAFT
        val open = row.openAmount?.let { Decimal.parseOrNull(it) } ?: return SalesRowStatus.ISSUED
        return when {
            open.signum <= 0 -> SalesRowStatus.PAID
            shownBucket == SalesInvoiceBucket.OVERDUE -> SalesRowStatus.OVERDUE
            else -> SalesRowStatus.OPEN
        }
    }

    /**
     * Which steps of the Verlauf an invoice has reached: it was drafted (always), issued (no longer a
     * draft), archived (its PDF is in the Dokumente) and paid (issued, with an open amount of zero —
     * an invoice with no open amount at all was never tracked, so it is not claimed as paid).
     */
    fun history(
        status: String,
        documentId: Long?,
        openAmount: String?,
    ): List<Pair<SalesHistoryStep, Boolean>> {
        val issued = status != "DRAFT"
        val settled = openAmount?.let { Decimal.parseOrNull(it) }?.let { it.signum <= 0 } ?: false
        return listOf(
            SalesHistoryStep.DRAFTED to true,
            SalesHistoryStep.ISSUED to issued,
            SalesHistoryStep.ARCHIVED to (documentId != null),
            SalesHistoryStep.PAID to (issued && settled),
        )
    }

    /** True when [openAmount] is a positive amount: there is still something to settle. */
    fun hasOpenAmount(openAmount: String?): Boolean = openAmount?.let { Decimal.parseOrNull(it) }?.let { it.signum > 0 } ?: false

    private const val CREDIT_NOTE_TYPE_CODE = "381"
}
