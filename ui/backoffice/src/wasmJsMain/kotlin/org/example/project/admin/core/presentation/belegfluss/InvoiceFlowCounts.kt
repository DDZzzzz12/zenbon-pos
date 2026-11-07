package org.example.project.admin.core.presentation.belegfluss

import org.example.project.core.domain.dto.document.InvoiceListDto

/**
 * The live figures the strip and the next-step bar are built from: the server's per-bucket counts
 * and the open-money sums of the payment buckets (decimal strings, never numbers).
 *
 * An empty [counts] map means "not loaded yet", not "all zero", so the strip draws a dash instead of
 * claiming nothing is waiting.
 */
data class InvoiceFlowCounts(
    val counts: Map<String, Long> = emptyMap(),
    val sums: Map<String, String> = emptyMap(),
    val currency: String = "EUR",
    /** Buckets whose figure this screen cannot state; their stage draws a dash rather than a wrong zero. */
    val unavailable: Set<InvoiceListDto.Bucket> = emptySet(),
) {
    val isLoaded: Boolean get() = counts.isNotEmpty()

    /**
     * The strip with its *Zuordnen* tile stated as **open payments** ([OpenPaymentsSummary]), the one
     * meaning it has on every screen. A null [payments] (no right to read the queue, or the request
     * failed) marks the tile unavailable — a dash, never a wrong 0. Unloaded counts stay unloaded.
     */
    fun withOpenPayments(payments: OpenPaymentsSummary?): InvoiceFlowCounts {
        if (!isLoaded) return this
        val key = InvoiceListDto.Bucket.OFFEN.name
        val bucket = InvoiceListDto.Bucket.OFFEN
        if (payments == null) return copy(sums = sums - key, unavailable = unavailable + bucket)
        return copy(
            counts = counts + (key to payments.total),
            sums = if (payments.sum != null) sums + (key to payments.sum) else sums - key,
            currency = payments.currency ?: currency,
            unavailable = unavailable - bucket,
        )
    }

    fun countOf(bucket: InvoiceListDto.Bucket): Long = counts[bucket.name] ?: 0L

    fun sumOf(bucket: InvoiceListDto.Bucket): String? = sums[bucket.name]

    /** Null while unknown, so a stage never shows a zero it has not been told. */
    fun countOf(stage: InvoiceFlowStage): Long? = if (isLoaded && stage.bucket !in unavailable) countOf(stage.bucket) else null
}
