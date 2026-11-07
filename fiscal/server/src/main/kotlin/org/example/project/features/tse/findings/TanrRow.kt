package org.example.project.features.tse.findings

import kotlinx.datetime.LocalDateTime

/**
 * One recorded TSE transaction number: the serial in hex, the TANR, and when the POS recorded it.
 *
 * [recordedAt] mirrors the nullable `tse_transaction.start_time`. A finding is attributed to the
 * period of the row FOLLOWING it, so a row with no start time drops out of every window — including
 * the unbounded whole-history one — and a gap whose following row is such a row is not reported at
 * all. Every live writer sets the column ([org.example.project.features.tse.data.service.TseVorgangSigner]
 * passes `currentTimeStamp()` on both the NORMAL and the SIMULATION path), so this is a note, not a
 * known hole; treating a null as "always in period" would instead put historic rows into whatever
 * period happened to be asked for.
 */
data class TanrRow(
    val serialHex: String,
    val tanr: Long,
    val recordedAt: LocalDateTime?,
    /** The TSE client that signed it (`tse_transaction.client_id`); scopes a single-client export's coverage. */
    val clientId: String? = null,
)
