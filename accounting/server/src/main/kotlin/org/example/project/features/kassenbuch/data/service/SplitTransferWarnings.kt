/**
 * Detects Geldtransit (book-to-book cash transfer) pairs that have only one leg present in a
 * cashBookIds-filtered DATEV export selection.
 *
 * Every leg of a transfer books against DATEV account 1460 (see [CashBookTransferServiceImpl]);
 * 1460 only nets to zero when BOTH legs land in the same export file. Exporting a single leg is
 * not wrong — a cash book can legitimately be exported on its own — but it is incomplete, and an
 * accountant handed an unbalanced Buchungsstapel without warning is the real problem this guards
 * against. Detection is informational only: it never fails the export.
 *
 * Shared by every DATEV export service that filters [KassenbuchHeader] rows by cash book, so the
 * "which transfers got split" logic lives in exactly one place.
 */
package org.example.project.features.kassenbuch.data.service

import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader

object SplitTransferWarnings {
    /** The DATEV account every Geldtransit leg books against — see class doc. */
    private const val TRANSIT_ACCOUNT = "1460"

    /**
     * @param entries The already date/cash-book-filtered headers about to be exported.
     * @return A single-element warning list naming how many transfers were split, or empty if
     *         none were (including when [entries] contains no transfers at all).
     */
    fun detect(entries: List<KassenbuchHeader>): List<String> {
        val splitTransferCount =
            entries
                .filter { it.transferId != null }
                .groupBy { it.transferId }
                .count { (_, legs) -> legs.size == 1 }

        if (splitTransferCount == 0) return emptyList()

        return listOf(
            "$splitTransferCount Geldtransit(s) have only one leg in this selection — account " +
                "$TRANSIT_ACCOUNT will not clear. Include all involved cash books for a balanced " +
                "Buchungsstapel.",
        )
    }
}
