package org.example.project.tse.utils

import org.example.project.core.domain.dto.tse.TseDto

/**
 * Software-side filtered TSE export for TSE 2.0.
 *
 * On TSE 2.0 the hardware `export_tar_filtered_time` / `export_tar_filtered_transaction` calls are
 * gone — the vendor's instruction is *"if an ERS requires a filtered export, the ERS must filter
 * the TAR themselves."* This takes the full-export TAR, decodes each member as a TR-03151 log
 * message, and keeps a member unless it is a **transaction** log message that falls outside the
 * requested range. Everything else — `info.csv`, certificates, and system/audit log messages — is
 * retained verbatim, so the filtered archive stays a conformant, self-contained export.
 *
 * Time and transaction values come out of the ASN.1 DER via [Tr03153LogMessageParser]; `WormEntry`
 * exposes neither, which is why the format-v3 parser is a hard prerequisite for this feature.
 */
internal object FilteredTarExport {

    /** Keep transaction log messages whose transactionNumber is in [start, end] (inclusive). */
    fun byTransactionNumber(fullTar: ByteArray, start: Long, end: Long, clientId: String?): ByteArray =
        filterTransactions(fullTar) { parsed ->
            val number = parsed.transactionNumber
            number != null && number in start..end && clientMatches(parsed, clientId)
        }

    /** Keep transaction log messages whose logTime (unix seconds) is in [start, end] (inclusive). */
    fun byTime(fullTar: ByteArray, startEpochSeconds: Long, endEpochSeconds: Long, clientId: String?): ByteArray =
        filterTransactions(fullTar) { parsed ->
            val time = parsed.logTime
            time != null && time in startEpochSeconds..endEpochSeconds && clientMatches(parsed, clientId)
        }

    private fun clientMatches(parsed: TseDto.ParsedLogMessageDto, clientId: String?): Boolean =
        clientId.isNullOrEmpty() || parsed.clientId == clientId

    /**
     * Rebuild the archive keeping every non-transaction member and only the transaction log
     * messages for which [keep] is true. A member is treated as a transaction log message iff it
     * parses and carries a transactionNumber; anything else (metadata, certificates, system/audit
     * logs, or bytes that do not parse) is retained.
     */
    private fun filterTransactions(
        fullTar: ByteArray,
        keep: (TseDto.ParsedLogMessageDto) -> Boolean,
    ): ByteArray {
        val kept = UstarArchive.read(fullTar).filter { member ->
            val parsed = Tr03153LogMessageParser.parse(member.content)
            if (parsed?.transactionNumber == null) true else keep(parsed)
        }
        return UstarArchive.write(kept)
    }
}
