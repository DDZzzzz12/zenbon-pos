package org.example.project.features.tse.verification

import org.example.project.core.domain.dto.tse.TseDto

/**
 * What a TSE archive holds, read from its parsed log messages: the one device it comes from and the
 * TANR, signature-counter and log-time ranges it spans (null when it holds no message carrying that
 * value). [of] returns null for a file holding no log message of any device — nothing to register.
 */
data class TseArchiveSummary(
    val serialHex: String,
    val tanrFrom: Long?,
    val tanrTo: Long?,
    val signatureCounterFrom: Long?,
    val signatureCounterTo: Long?,
    val logTimeFrom: Long?,
    val logTimeTo: Long?,
) {
    companion object {
        /**
         * @throws IllegalArgumentException when the records come from more than one device — an
         *   archive is one TSE's export, and a mixed file cannot be attributed to a serial.
         */
        fun of(records: List<TseDto.ParsedLogMessageDto>): TseArchiveSummary? {
            val serials = records.mapNotNull { it.serialNumberHex?.uppercase()?.takeIf(String::isNotBlank) }.toSet()
            if (serials.isEmpty()) return null
            require(serials.size == 1) { "the archive holds log messages of ${serials.size} TSEs (${serials.joinToString()})" }
            val tanrs = records.mapNotNull { it.transactionNumber }
            val counters = records.mapNotNull { it.signatureCounter }
            val times = records.mapNotNull { it.logTime }
            return TseArchiveSummary(
                serialHex = serials.single(),
                tanrFrom = tanrs.minOrNull(),
                tanrTo = tanrs.maxOrNull(),
                signatureCounterFrom = counters.minOrNull(),
                signatureCounterTo = counters.maxOrNull(),
                logTimeFrom = times.minOrNull(),
                logTimeTo = times.maxOrNull(),
            )
        }
    }
}
