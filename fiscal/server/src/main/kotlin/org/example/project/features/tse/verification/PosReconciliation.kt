package org.example.project.features.tse.verification

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.Severity

/**
 * The TSE export against the POS (design 2026-09-22 §4.7). The TSE log is continuous even when a
 * signature was lost on the POS side, so only this comparison finds such TANRs. The parser does not
 * extract processData (tag [2]), so a finding is described by its operations and process type;
 * telling an orphan abort from a double signature needs optional Task 5.8.
 *
 * Pure: records in, findings out. Nothing here touches the database.
 */
object PosReconciliation {
    fun reconcile(
        records: List<TseDto.ParsedLogMessageDto>,
        knownTanrsBySerial: Map<String, Set<Long>>,
    ): List<FindingDto> =
        records
            .filter { it.transactionNumber != null && !it.serialNumberHex.isNullOrBlank() }
            .groupBy { it.serialNumberHex!!.uppercase() to it.transactionNumber!! }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .mapNotNull { (key, logs) ->
                val (serial, tanr) = key
                val known = knownTanrsBySerial[serial] ?: return@mapNotNull null
                if (tanr in known) return@mapNotNull null
                val operations = logs.mapNotNull { it.operationType }.distinct().joinToString(",")
                val processType = logs.firstNotNullOfOrNull { it.processType }.orEmpty()
                FindingDto(
                    severity = Severity.WARNING,
                    code = FindingCode.TANR_WITHOUT_POS_RECORD,
                    message =
                        "TSE transaction $tanr has no POS record (operations: $operations" +
                            "${if (processType.isEmpty()) "" else "; processType: $processType"})",
                    detail = "serial=$serial;tanr=$tanr;operations=$operations;processType=$processType",
                )
            }
}
