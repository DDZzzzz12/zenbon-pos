/**
 * Splits a Z-Bon's Kassenbuch entries into the sections the closing document renders.
 *
 * Geldtransit and Kassendifferenz get their own sections because neither is stated anywhere else
 * on a Z-Bon: the report shows that the balance fell, never that the cash is now in the Tresor,
 * and the drawer count has no other outlet at all.
 *
 * The partition is TOTAL — every input entry appears in exactly one bucket. That is what stops a
 * genuine cash payout from disappearing off the day's closing document, and it is asserted
 * directly rather than assumed.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType

/** The four sections of a Z-Bon's Kassenbuch block, in render order. */
data class ZBonKassenbuchSections(
    val transit: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    val differences: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    val income: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
    val expenses: List<KassenbuchDto.KassenbuchGroupedSummaryDto>,
)

/** Geldtransit — the cash moved between the shop's own books. */
private val TRANSIT_TYPES =
    setOf(KassenbuchEntryType.CASH_TRANSFER_OUT, KassenbuchEntryType.CASH_TRANSFER_IN)

/** Kassendifferenz — the output of a drawer count. */
private val DIFFERENCE_TYPES =
    setOf(KassenbuchEntryType.CASH_SURPLUS, KassenbuchEntryType.CASH_SHORTAGE)

/**
 * Partitions [entries] into the Z-Bon's four sections, preserving input order within each.
 *
 * A null or empty input yields four empty lists. Call sites must not re-derive which entry types
 * count as transit or difference — that is how the printed Z-Bon and the PDF drift apart.
 *
 * @param entries The Z-Bon's Kassenbuch summary, or null when it was not requested
 */
fun partitionZBonKassenbuch(entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto>?): ZBonKassenbuchSections {
    val all = entries.orEmpty()
    val rest = all.filter { it.entryType !in TRANSIT_TYPES && it.entryType !in DIFFERENCE_TYPES }
    return ZBonKassenbuchSections(
        transit = all.filter { it.entryType in TRANSIT_TYPES },
        differences = all.filter { it.entryType in DIFFERENCE_TYPES },
        income = rest.filter { it.entryType.isIncoming },
        expenses = rest.filter { !it.entryType.isIncoming },
    )
}
