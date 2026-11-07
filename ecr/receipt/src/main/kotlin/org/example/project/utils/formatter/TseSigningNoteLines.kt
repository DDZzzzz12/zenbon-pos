package org.example.project.utils.formatter

import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.utils.lateSigningReasonLabelDe

private val RECEIPT_DATE_TIME: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter
        .ofPattern("dd.MM.yyyy HH:mm:ss")

/**
 * The Beleg lines for a bill that could not be TSE-signed immediately. German, like every other
 * fiscal line on the Beleg. The reason is the customer-readable label of its code, never the raw
 * server error text.
 */
fun tseSigningNoteLines(note: TseSigningNoteDto): List<String> {
    val status =
        when {
            note.signedAt != null -> listOf("Nachsigniert am ${formatReceiptDateTime(note.signedAt!!)}")
            note.abandoned -> listOf("Nachsignierung abgebrochen")
            else -> listOf("TSE nicht erreichbar –", "Beleg wird nachsigniert")
        }
    return status + "Grund: ${lateSigningReasonLabelDe(note.reasonCode)}"
}

/** ISO local date-time as stored by the server → `dd.MM.yyyy HH:mm:ss`; unparseable text as is. */
private fun formatReceiptDateTime(isoLocal: String): String =
    runCatching { RECEIPT_DATE_TIME.format(java.time.LocalDateTime.parse(isoLocal)) }.getOrDefault(isoLocal)
