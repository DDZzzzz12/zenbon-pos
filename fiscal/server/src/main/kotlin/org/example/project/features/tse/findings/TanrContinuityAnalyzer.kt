package org.example.project.features.tse.findings

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.tse.TseFindingKind

/**
 * Finds TANRs the device issued that the POS does not account for (AEAO § 146a Nr. 1.15.1: gaps
 * must be recognisable; Swissbit AGD §5.2: a gap must be analysed). Works on the POS data alone,
 * so no TSE export is needed. A gap belongs to the period of the row that follows it. Numbers
 * before a device's first recorded TANR are not reported, because the POS cannot know where
 * that device's history began.
 */
object TanrContinuityAnalyzer {
    fun analyze(
        rows: List<TanrRow>,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<TanrFinding> =
        rows
            .groupBy { it.serialHex }
            .toSortedMap()
            .flatMap { (serial, deviceRows) ->
                // Sorted per device: listing every duplicate before every gap would read as two
                // unrelated lists, while an operator works down one device's numbers in order.
                (duplicates(serial, deviceRows, start, end) + gaps(serial, deviceRows, start, end))
                    .sortedBy { it.tanrFrom }
            }

    private fun inPeriod(
        row: TanrRow,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Boolean = row.recordedAt != null && row.recordedAt >= start && row.recordedAt < end

    private fun gaps(
        serial: String,
        rows: List<TanrRow>,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<TanrFinding> {
        val byTanr = rows.groupBy { it.tanr }.toSortedMap()
        val tanrs = byTanr.keys.toList()
        return tanrs.zipWithNext().mapNotNull { (a, b) ->
            val after = byTanr.getValue(b)
            if (b - a > 1 && after.any { inPeriod(it, start, end) }) TanrFinding(serial, a + 1, b - 1, TseFindingKind.GAP) else null
        }
    }

    private fun duplicates(
        serial: String,
        rows: List<TanrRow>,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<TanrFinding> =
        rows
            .groupBy { it.tanr }
            .filter { (_, same) -> same.size > 1 && same.any { inPeriod(it, start, end) } }
            .keys
            .sorted()
            .map { TanrFinding(serial, it, it, TseFindingKind.DUPLICATE) }
}
