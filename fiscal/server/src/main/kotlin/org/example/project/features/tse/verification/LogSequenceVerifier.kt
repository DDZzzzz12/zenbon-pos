package org.example.project.features.tse.verification

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.Severity
import org.example.project.core.domain.dto.tse.TseVerificationDto.Verdict

/**
 * AGD §5.2 sequence-plausibility checks over a TSE export's log messages. Pure — no I/O, no device.
 * Records are examined in signatureCounter order (the order the TSE signed them).
 */
object LogSequenceVerifier {
    /** Missing-counter runs up to this size are tolerated (single power-loss gaps). */
    private const val MAX_SINGLE_GAP = 1L

    /** operationTypes (lower-cased) that legitimately move the clock in either direction. */
    private val SET_TIME_OPERATION_TYPES = setOf("updatetime", "settime")

    /**
     * @param singleClient the archive holds ONE client's log messages (a client-filtered export).
     *   A TSE numbers every client's transactions in one TANR sequence and one signature-counter
     *   sequence, so such an archive has holes in both by construction; the gap checks are skipped and
     *   its completeness is judged against the POS's records of that client instead
     *   ([ExportCoverage]). Duplicate counters and timestamp regressions are still checked.
     */
    fun verify(
        records: List<TseDto.ParsedLogMessageDto>,
        singleClient: Boolean = false,
    ): TseVerificationDto.ReportDto {
        val findings = mutableListOf<FindingDto>()

        val ordered = records.filter { it.signatureCounter != null }.sortedBy { it.signatureCounter }
        val counters = ordered.mapNotNull { it.signatureCounter }
        val txNumbers = records.mapNotNull { it.transactionNumber }.distinct().sorted()

        val unparseableCount = records.size - ordered.size
        if (unparseableCount > 0) {
            findings +=
                FindingDto(
                    Severity.WARNING,
                    FindingCode.UNPARSEABLE_RECORD,
                    "$unparseableCount record(s) could not be ordered (missing signature counter).",
                    "count=$unparseableCount",
                )
        }

        if (txNumbers.isEmpty()) {
            findings +=
                FindingDto(
                    Severity.INFO,
                    FindingCode.NO_TRANSACTION_LOGS,
                    "No transaction log messages were found in the archive.",
                )
        }

        // 1. Duplicate signature counters — CRITICAL.
        counters.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (value, count) ->
            findings +=
                FindingDto(
                    Severity.CRITICAL,
                    FindingCode.DUPLICATE_SIGNATURE_COUNTER,
                    "Signature counter $value appears $count times; no counter may be present twice.",
                    "counter=$value;count=$count",
                )
        }

        // 2. Signature-counter gaps beyond MAX_SINGLE_GAP — WARNING. Not for one client's export:
        // the other clients' signatures are the gaps.
        val distinctCounters = if (singleClient) emptyList() else counters.distinct().sorted()
        for (i in 1 until distinctCounters.size) {
            val missing = distinctCounters[i] - distinctCounters[i - 1] - 1
            if (missing > MAX_SINGLE_GAP) {
                findings +=
                    FindingDto(
                        Severity.WARNING,
                        FindingCode.SIGNATURE_COUNTER_GAP,
                        "$missing signature counters missing between ${distinctCounters[i - 1]} and ${distinctCounters[i]}.",
                        "from=${distinctCounters[i - 1]};to=${distinctCounters[i]};missing=$missing",
                    )
            }
        }

        // 3. Transaction-number holes (contiguous within the present range) — CRITICAL. Not for one
        // client's export: the other clients' TANRs are the holes (ExportCoverage checks its own).
        val contiguityChecked = if (singleClient) emptyList() else txNumbers
        for (i in 1 until contiguityChecked.size) {
            if (contiguityChecked[i] != contiguityChecked[i - 1] + 1) {
                findings +=
                    FindingDto(
                        Severity.CRITICAL,
                        FindingCode.TRANSACTION_NUMBER_GAP,
                        "Missing transaction numbers between ${contiguityChecked[i - 1]} and ${contiguityChecked[i]}.",
                        "from=${contiguityChecked[i - 1]};to=${contiguityChecked[i]}",
                    )
            }
        }

        // 4. Timestamp regressions not explained by a set-time event — WARNING. Null-logTime
        // records (parseable but time-less) are transparent: they neither break nor create a
        // comparison, but a set-time event among them still exempts the next real jump.
        // A set-time event exempts BOTH itself and the record that follows it:
        //  - itself, because a real Swissbit stamps the UpdateTime log message with the
        //    corrected time; a fast-running clock therefore makes the sync record step back
        //    (Auth @…705 -> UpdateTime @…689 in a real TAR);
        //  - the next record, because a device that stamps the sync with the old time shows the
        //    step on the following record instead (the virtual TSE does this).
        // The flag is set AFTER the compare-and-reset below so the second case still works for a
        // set-time record that carries a real logTime. A regression between two non-sync records
        // is never exempted.
        var lastTime: Long? = null
        var setTimeSinceLast = false
        for (record in ordered) {
            val time = record.logTime
            if (time != null) {
                val prev = lastTime
                val explainedBySetTime = setTimeSinceLast || isSetTime(record)
                if (prev != null && time < prev && !explainedBySetTime) {
                    findings +=
                        FindingDto(
                            Severity.WARNING,
                            FindingCode.TIMESTAMP_REGRESSION,
                            "Timestamp moved backward ($prev -> $time) at signature counter " +
                                "${record.signatureCounter} without a preceding set-time event.",
                            "counter=${record.signatureCounter};prev=$prev;curr=$time",
                        )
                }
                lastTime = time
                setTimeSinceLast = false
            }
            if (isSetTime(record)) setTimeSinceLast = true
        }

        return TseVerificationDto.ReportDto(
            verdict = verdictOf(findings),
            recordCount = records.size,
            transactionCount = txNumbers.size,
            signatureCounterMin = counters.minOrNull(),
            signatureCounterMax = counters.maxOrNull(),
            findings = findings,
        )
    }

    /**
     * The single verdict rule, shared with [TseVerificationService], which adds POS-reconciliation
     * findings after this verifier has run. Duplicating it would let the two disagree.
     */
    fun verdictOf(findings: List<FindingDto>): Verdict =
        when {
            findings.any { it.severity == Severity.CRITICAL } -> Verdict.FAIL
            findings.any { it.severity == Severity.WARNING } -> Verdict.PASS_WITH_WARNINGS
            else -> Verdict.PASS
        }

    private fun isSetTime(record: TseDto.ParsedLogMessageDto): Boolean = record.operationType?.lowercase() in SET_TIME_OPERATION_TYPES
}
