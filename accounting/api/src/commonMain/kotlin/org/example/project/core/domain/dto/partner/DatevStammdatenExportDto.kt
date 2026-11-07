package org.example.project.core.domain.dto.partner

import kotlinx.serialization.Serializable

/**
 * Wire contract for the DATEV Debitoren-/Kreditorenstamm export.
 *
 * The result is a file **plus a report of what did not make it into it**. That pairing is the point:
 * a party whose role carries no Personenkonto cannot be exported (`Konto` is the one genuinely
 * mandatory field, and inventing an account number for an accounting handover is not acceptable),
 * but one unnumbered creditor must not deny the advisor the other two hundred. So the export
 * completes and *names* what it left out, rather than failing or — worse — silently dropping rows.
 */
object DatevStammdatenExportDto {
    /**
     * Which Personenkonto an issue is about.
     *
     * A company that is both a supplier and a customer produces **two** accounts, so an issue has to
     * say which one it concerns; naming only the party would be ambiguous exactly for the parties
     * this export exists to handle well.
     */
    @Serializable
    enum class StammdatenRole {
        /** Creditor — `supplier.creditor_no`. */
        KREDITOR,

        /** Debtor — `debtor.debtor_no`. */
        DEBITOR,
    }

    /**
     * Why a row is missing or incomplete.
     *
     * A stable code rather than a sentence alone: the UI decides the wording and the grouping, and a
     * German string parsed out of [StammdatenIssueDto.message] would be a contract by accident.
     */
    @Serializable
    enum class StammdatenIssueCode {
        /**
         * The role has no account number, so **no row was written for it**. Fatal for that one
         * account, harmless for the rest of the file.
         */
        MISSING_ACCOUNT_NUMBER,

        /**
         * The party's postcode contains something other than digits, so the row was written with
         * `Postleitzahl` empty. DATEV's own regex rejects a letter there and refuses the whole file,
         * so emitting it would cost the advisor every other row too.
         */
        NON_NUMERIC_POSTCODE,
    }

    /**
     * One thing the operator should know about one account.
     *
     * [konto] is null exactly when the account has no number — which is the reason it was skipped.
     */
    @Serializable
    data class StammdatenIssueDto(
        val code: StammdatenIssueCode,
        val role: StammdatenRole,
        val partnerId: Long,
        val partnerName: String,
        val konto: String? = null,
        /** Ready-to-render English text. Advisory: [code] is what a caller should branch on. */
        val message: String,
    )

    /**
     * The generated file and its report.
     *
     * Carries the raw [bytes] rather than Base64 or a path, matching
     * `TransientExportResult` on the Buchungsstapel side so a route handler streams both exports the
     * same way. Not `@Serializable` for the same reason: a [ByteArray] belongs in the response body,
     * not inside a JSON envelope.
     */
    data class ResultDto(
        val bytes: ByteArray,
        val fileName: String,
        /** Rows in the file — accounts, not parties. */
        val accountCount: Int,
        /** Accounts that produced no row. See [StammdatenIssueCode.MISSING_ACCOUNT_NUMBER]. */
        val skipped: List<StammdatenIssueDto> = emptyList(),
        /** Rows that were written with a field dropped. Non-fatal. */
        val warnings: List<StammdatenIssueDto> = emptyList(),
    ) {
        // Hand-written because a data class compares a ByteArray by identity, which would make two
        // byte-identical exports unequal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ResultDto) return false
            return fileName == other.fileName &&
                bytes.contentEquals(other.bytes) &&
                accountCount == other.accountCount &&
                skipped == other.skipped &&
                warnings == other.warnings
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + accountCount
            result = 31 * result + skipped.hashCode()
            result = 31 * result + warnings.hashCode()
            return result
        }
    }
}
