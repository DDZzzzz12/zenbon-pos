package org.example.project.features.tse.verification

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.Severity
import org.example.project.features.dsfinvk.data.service.TseTarCoverage
import org.example.project.features.tse.findings.TanrRow
import kotlin.time.Instant

/**
 * Does a TSE export hold every transaction the POS signed inside the range the export covers?
 *
 * [PosReconciliation] only looks one way — TSE transactions the POS has no record of. The other
 * way was never checked, so a filtered export that silently held none of the till's transactions
 * verified PASS (real-TSE QA rt12: a day with 26 signed transactions exported 0 and passed; so did
 * a 0-byte file). An auditor reads PASS as "this archive is the period".
 *
 * The verify call names only a file, so the range comes from the archive itself, per device serial:
 *  - its **TANR span** — the lowest to the highest transaction number it carries for that serial;
 *  - its **log-time span** — the earliest to the latest `logTime` of ANY of its log messages
 *    (system and audit logs included, which is what still dates an export holding no transaction),
 *    narrowed by [CLOCK_TOLERANCE_SECONDS] at both ends because the POS stamps `start_time` with the
 *    server clock, not the TSE's.
 *
 * A POS transaction (NORMAL mode, with a TANR, same serial) inside either span and absent from the
 * archive is a CRITICAL finding — the same condition the DSFinV-K export refuses to ship
 * (`TSE_EXPORT_INCOMPLETE`), and it uses the same `(serial, TANR)` comparison, [TseTarCoverage].
 * Pure: records and rows in, findings out.
 */
object ExportCoverage {
    /** Server clock vs TSE clock: a transaction this close to the archive's edge is not judged by time. */
    const val CLOCK_TOLERANCE_SECONDS = 60L

    /** Missing TANRs listed per finding before the list is cut short (the count is always complete). */
    private const val LISTED_TANRS = 20

    /**
     * @param filter the filter the export was made with, from its sidecar; null judges the file as an
     *   unfiltered (all-client) export, exactly as before sidecars existed. For a single-client filter
     *   only that client's POS transactions are required — the TSE was asked for nothing else — and
     *   they are required across the REQUESTED range too, not only the range the archive happens to
     *   span, so an export that lost its first or last transaction is still caught.
     * @param archived TANR ranges registered archives hold for erased entries. A transaction required
     *   ONLY because it lies in the requested range is excused when an archive holds it — the device
     *   no longer can, exactly as the all-client check (which reads the range from the file itself)
     *   never asks for it. One the file's own span or log-time window reaches is still required: it
     *   should be on the device. Excused transactions are reported per archive as INFO.
     */
    fun missingPosTransactions(
        records: List<TseDto.ParsedLogMessageDto>,
        posRows: List<TanrRow>,
        zone: TimeZone,
        filter: TseExportFilterSidecar? = null,
        archived: List<ArchivedTanrRange> = emptyList(),
    ): List<FindingDto> {
        val serials = records.mapNotNull { it.serialNumberHex?.uppercase()?.takeIf(String::isNotBlank) }.toSet()
        if (serials.isEmpty()) return emptyList()
        val tanrSpans = tanrSpansBySerial(records)
        val window = logTimeWindow(records, zone)
        val singleClient = filter?.takeIf { it.isSingleClient }
        val requestedTanrs = singleClient?.requestedTanrSpan()
        val requestedWindow = singleClient?.requestedWindow(zone)
        val candidates =
            posRows
                .filter { row -> row.serialHex in serials }
                .filter { row -> singleClient == null || row.clientId == singleClient.clientId }
        val inOwnRange = candidates.filter { row -> row.tanr inSpan tanrSpans[row.serialHex] || row.recordedAt inWindow window }
        val requestedOnly =
            (candidates - inOwnRange.toSet())
                .filter { row -> row.tanr inSpan requestedTanrs || row.recordedAt inWindow requestedWindow }
        val excused = requestedOnly.associateWith { row -> archived.firstOrNull { it.serialHex == row.serialHex && row.tanr in it.tanrs } }
        val required =
            (inOwnRange + requestedOnly.filter { excused[it] == null })
                .groupBy({ it.serialHex }, { it.tanr })
                .mapValues { (_, tanrs) -> tanrs.toSet() }
        return archiveFindings(excused) + gapFindings(required, records)
    }

    /** One INFO per archive that holds requested transactions the device no longer has. */
    private fun archiveFindings(excused: Map<TanrRow, ArchivedTanrRange?>): List<FindingDto> =
        excused.entries
            .filter { it.value != null }
            .groupBy({ it.value!! }, { it.key.tanr })
            .map { (archive, tanrs) ->
                FindingDto(
                    severity = Severity.INFO,
                    code = FindingCode.ERASED_ENTRIES_IN_ARCHIVE,
                    message =
                        "${tanrs.size} transaction(s) of the requested range (TANR ${tanrs.min()}–${tanrs.max()}) were erased " +
                            "from TSE ${archive.serialHex} by an export-and-delete and are held by the registered archive " +
                            "${archive.fileName}; they are not expected in this file.",
                    detail = "serial=${archive.serialHex};archive=${archive.fileName};count=${tanrs.size}",
                )
            }

    private fun gapFindings(
        required: Map<String, Set<Long>>,
        records: List<TseDto.ParsedLogMessageDto>,
    ): List<FindingDto> {
        if (required.isEmpty()) return emptyList()
        return TseTarCoverage.gaps(required, records).map { gap ->
            val listed = gap.missingTanrs.take(LISTED_TANRS).joinToString(",")
            val more = if (gap.missingTanrs.size > LISTED_TANRS) ",…" else ""
            FindingDto(
                severity = Severity.CRITICAL,
                code = FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT,
                message =
                    "${gap.missingTanrs.size} of ${gap.requiredCount} transaction(s) the POS signed on TSE " +
                        "${gap.serialHex} within the range of this export are not in it (TANR $listed$more). " +
                        "The archive does not cover its own period.",
                detail = "serial=${gap.serialHex};missingCount=${gap.missingTanrs.size};missing=$listed$more",
            )
        }
    }

    private fun TseExportFilterSidecar.requestedTanrSpan(): LongRange? {
        val from = startTransaction ?: return null
        val to = endTransaction ?: return null
        return from..to
    }

    /** The requested time range, narrowed by [CLOCK_TOLERANCE_SECONDS] at both ends like the log-time span. */
    private fun TseExportFilterSidecar.requestedWindow(zone: TimeZone): ClosedRange<LocalDateTime>? {
        val from = (startEpochMillis ?: return null) / 1000 + CLOCK_TOLERANCE_SECONDS
        val to = (endEpochMillis ?: return null) / 1000 - CLOCK_TOLERANCE_SECONDS
        if (from > to) return null
        return from.toLocal(zone)..to.toLocal(zone)
    }

    private fun tanrSpansBySerial(records: List<TseDto.ParsedLogMessageDto>): Map<String, LongRange> =
        records
            .filter { it.transactionNumber != null && !it.serialNumberHex.isNullOrBlank() }
            .groupBy({ it.serialNumberHex!!.uppercase() }, { it.transactionNumber!! })
            .mapValues { (_, tanrs) -> tanrs.min()..tanrs.max() }

    private fun logTimeWindow(
        records: List<TseDto.ParsedLogMessageDto>,
        zone: TimeZone,
    ): ClosedRange<LocalDateTime>? {
        val times = records.mapNotNull { it.logTime }
        if (times.isEmpty()) return null
        val from = times.min() + CLOCK_TOLERANCE_SECONDS
        val to = times.max() - CLOCK_TOLERANCE_SECONDS
        if (from > to) return null
        return from.toLocal(zone)..to.toLocal(zone)
    }

    private fun Long.toLocal(zone: TimeZone): LocalDateTime = Instant.fromEpochSeconds(this).toLocalDateTime(zone)

    private infix fun Long.inSpan(span: LongRange?): Boolean = span != null && this in span

    private infix fun LocalDateTime?.inWindow(window: ClosedRange<LocalDateTime>?): Boolean = this != null && window != null && this in window
}
