package org.example.project.core.domain.dto.tse

import kotlinx.serialization.Serializable

/** DTOs for TSE log-sequence plausibility verification (AGD §5.2 / OE.Transaction). */
object TseVerificationDto {
    @Serializable
    enum class Verdict { PASS, PASS_WITH_WARNINGS, FAIL }

    @Serializable
    enum class Severity { CRITICAL, WARNING, INFO }

    @Serializable
    enum class FindingCode {
        DUPLICATE_SIGNATURE_COUNTER,
        SIGNATURE_COUNTER_GAP,
        TRANSACTION_NUMBER_GAP,
        TIMESTAMP_REGRESSION,
        NO_TRANSACTION_LOGS,
        UNPARSEABLE_RECORD,
        TANR_WITHOUT_POS_RECORD,

        /** The file holds no TSE log message at all (0 bytes, not a TAR, or info.csv only): it proves nothing. CRITICAL. */
        EMPTY_ARCHIVE,

        /**
         * The POS signed transactions inside the range the export covers (its TANR span or its
         * log-time span) that the export does not contain — e.g. a filtered export that kept only one
         * client's transactions. The opposite direction of [TANR_WITHOUT_POS_RECORD]. CRITICAL.
         */
        POS_TRANSACTION_MISSING_FROM_EXPORT,

        /**
         * The file is a filtered export and was judged against the filter recorded beside it (its
         * `.json` sidecar): which client and which time or TANR range. For a single-client export the
         * TANR and signature-counter holes that other clients' transactions leave are expected and not
         * reported; completeness is checked against the POS's own records of that client. INFO.
         */
        EXPORT_FILTER_APPLIED,

        /**
         * The file has a filter sidecar whose SHA-256 does not match the file, so the sidecar was
         * ignored and the file judged as strictly as an unfiltered export. WARNING.
         */
        FILTER_SIDECAR_MISMATCH,

        /**
         * A single-client export's requested range reaches back into entries an export-and-delete
         * erased from the TSE; a registered archive holds them, so they are not required in this file.
         * The finding names the archive. INFO.
         */
        ERASED_ENTRIES_IN_ARCHIVE,
    }

    /** A single anomaly. [detail] carries the specific values (semicolon-separated key=value). */
    @Serializable
    data class FindingDto(
        val severity: Severity,
        val code: FindingCode,
        val message: String,
        val detail: String? = null,
    )

    @Serializable
    data class ReportDto(
        val verdict: Verdict,
        val recordCount: Int,
        /** Count of DISTINCT transaction numbers seen across [recordCount] records. */
        val transactionCount: Int,
        val signatureCounterMin: Long? = null,
        val signatureCounterMax: Long? = null,
        val findings: List<FindingDto> = emptyList(),
    )

    /** Request: the name of a file inside the server's configured exports directory. */
    @Serializable
    data class VerifyRequestDto(
        val fileName: String,
    )
}
