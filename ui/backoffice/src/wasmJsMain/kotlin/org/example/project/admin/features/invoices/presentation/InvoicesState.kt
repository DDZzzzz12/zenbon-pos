package org.example.project.admin.features.invoices.presentation

import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowCounts
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.presentation.error.UiError

/**
 * [bucket] is the applied bucket — what the last request used — so chips and list cannot disagree.
 *
 * [bucketSums] carries only the three **payment** buckets, as decimal strings. The capture buckets
 * are absent rather than zero: `ZU_ERFASSEN` holds documents nothing has been parsed out of yet, so
 * it has no amount at all, and a zero would read as "there is nothing worth capturing" instead of
 * "this question does not apply".
 */
data class InvoicesState(
    val bucket: InvoiceListDto.Bucket = InvoiceListDto.Bucket.ZU_ERFASSEN,
    val items: List<InvoiceListDto.RowDto> = emptyList(),
    val bucketCounts: Map<String, Long> = emptyMap(),
    val bucketSums: Map<String, String> = emptyMap(),
    val currency: String = "EUR",
    val isLoading: Boolean = false,
    val error: UiError? = null,
    /**
     * Whether the screen has already redirected itself away from an empty opening bucket.
     *
     * One shot: after it has happened once, or after the operator picks a bucket themselves, an
     * empty bucket stays empty. Otherwise clicking "Zu erfassen" to confirm it is clear would
     * bounce straight back out of it.
     */
    val hasAutoSelectedBucket: Boolean = false,
) {
    fun countOf(bucket: InvoiceListDto.Bucket): Long = bucketCounts[bucket.name] ?: 0L

    /** Null when this bucket has no money question — see [bucketSums]. */
    fun sumOf(bucket: InvoiceListDto.Bucket): String? = bucketSums[bucket.name]

    /** The figures the Belegfluss strip and the next-step bar are drawn from. */
    fun flowCounts(): InvoiceFlowCounts = InvoiceFlowCounts(bucketCounts, bucketSums, currency)
}
