package org.example.project.features.dsfinvk.data.service

import org.example.project.core.domain.dto.tse.TseDto

/**
 * Does a TSE TAR hold every signed transaction a DSFinV-K period references? (checklist 28.4)
 *
 * The package ships the attached device's FULL TAR (CONT-1). That device need not be the one that
 * signed the period: a swapped, wiped or wrong device — or a virtual TSE restarted without its
 * in-memory log — answers the export with a valid archive holding only `info.csv`. Such a TAR
 * verifies PASS with zero transactions, so without this comparison an auditor receives an archive
 * that looks complete and covers nothing.
 *
 * A TANR counts as covered when the TAR carries at least one log message with that transaction
 * number from that device's serial — the same `(serial, TANR)` key `PosReconciliation` uses in the
 * opposite direction. Pure: records in, gaps out; nothing here touches the database or the device.
 */
object TseTarCoverage {
    /**
     * @param requiredTanrsBySerial the period's signed TANRs, keyed by upper-case hex serial
     * @param tarRecords the TAR's parsed log messages
     * @return one gap per device with at least one uncovered TANR, ordered by serial; empty when covered
     */
    fun gaps(
        requiredTanrsBySerial: Map<String, Set<Long>>,
        tarRecords: List<TseDto.ParsedLogMessageDto>,
    ): List<TseTarCoverageGap> {
        val presentBySerial =
            tarRecords
                .filter { it.transactionNumber != null && !it.serialNumberHex.isNullOrBlank() }
                .groupBy({ it.serialNumberHex!!.uppercase() }, { it.transactionNumber!! })
                .mapValues { (_, tanrs) -> tanrs.toSet() }
        return requiredTanrsBySerial
            .toSortedMap()
            .mapNotNull { (serial, required) ->
                val present = presentBySerial[serial.uppercase()].orEmpty()
                val missing = (required - present).sorted()
                if (missing.isEmpty()) {
                    null
                } else {
                    TseTarCoverageGap(
                        serialHex = serial.uppercase(),
                        requiredCount = required.size,
                        missingTanrs = missing,
                        deviceInTar = present.isNotEmpty(),
                    )
                }
            }
    }

    /** The operator-facing refusal text: names every uncovered device and what is missing. */
    fun describe(gaps: List<TseTarCoverageGap>): String =
        gaps.joinToString("; ") { gap ->
            val range = "TANR ${gap.missingTanrs.first()}–${gap.missingTanrs.last()}"
            val where =
                if (gap.deviceInTar) {
                    "the archive holds this device but not all of its transactions"
                } else {
                    "the archive holds no transaction of this device"
                }
            "TSE serial ${gap.serialHex}: ${gap.missingTanrs.size} of ${gap.requiredCount} signed " +
                "transaction(s) missing ($range) — $where"
        }
}
