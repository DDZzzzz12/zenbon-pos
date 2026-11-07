package org.example.project.features.tse.verification

import kotlinx.serialization.Serializable

/**
 * What a filtered TSE export was asked for, written next to its TAR as `<file>.json`.
 *
 * A TSE numbers the transactions of ALL its clients in one TANR sequence and one signature-counter
 * sequence. A correct single-client export therefore has holes in both, and `/api/tse/verify` —
 * which only sees the file — reported it FAIL with two CRITICAL findings (real-TSE QA rt14). The
 * sidecar tells the verifier which filter produced the file, so it can judge the export against
 * that filter instead of against the whole device.
 *
 * Exactly one range is set: [startEpochMillis]/[endEpochMillis] for a time-filtered export,
 * [startTransaction]/[endTransaction] for a transaction-filtered one. [clientId] null means all
 * clients. [sha256] binds the sidecar to the bytes it describes: a sidecar whose hash does not match
 * its file is ignored, and the file is verified as strictly as an unfiltered one.
 */
@Serializable
data class TseExportFilterSidecar(
    val fileName: String,
    val clientId: String? = null,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val startTransaction: Long? = null,
    val endTransaction: Long? = null,
    /** ISO-8601 instant the export was requested. */
    val requestedAt: String,
    val sha256: String,
) {
    /** Only one client's transactions were asked for. */
    val isSingleClient: Boolean get() = !clientId.isNullOrBlank()
}
