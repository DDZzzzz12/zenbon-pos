/**
 * DTOs for cash books and the transfers between them.
 *
 * A shop has exactly one **fiscal** book (the Ladenkasse — the register drawer that §146a AO
 * reaches: TSE-signed, swept into the Z-Bon, exported to DSFinV-K) plus any number of non-fiscal
 * books (Tresor, Nebenkasse) which are ordinary bookkeeping.
 *
 * Every money field is a `String`, never a number — see the money rules in CLAUDE.md.
 */
package org.example.project.core.domain.dto.kassenbuch

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval

object CashBookDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val name: String,
        val isFiscal: Boolean,
        val datevAccount: String,
        val kost1: String? = null,
        val kost2: String? = null,
        val closeInterval: CashBookCloseInterval,
        val isActive: Boolean,
        val sortOrder: Int,
        /** Money as a decimal string — never a JSON number. */
        val currentBalance: String,
        /**
         * ISO 4217 code [currentBalance] is denominated in — the shop's configured default, the
         * same source the Kassenbuch summary reports. Carried per book so a client never has to
         * guess it from an unrelated payload: a book with no entries in the viewed period would
         * otherwise be rendered with whatever symbol the first loaded entry happened to use.
         */
        val currency: String,
        /**
         * The till whose TSE client signs the closing's cash movements (fiscal book only); null when
         * none is designated — the calling till then signs, and the adminApp cannot book movements.
         */
        val signingTerminalId: Long? = null,
        val signingTerminalName: String? = null,
    )

    /** Body of `PUT /api/v1/kassenbuch/books/{bookId}/signing-terminal`; null clears the designation. */
    @Serializable
    data class SigningTerminalRequestDto(
        val terminalId: Long? = null,
    )

    /** One till that may be designated: `GET /api/v1/kassenbuch/books/{bookId}/signing-terminal/candidates`. */
    @Serializable
    data class SigningTerminalCandidateDto(
        val terminalId: Long,
        val name: String,
        val clientId: String,
    )

    /**
     * Creating a book never creates a fiscal one — the shop's single fiscal Ladenkasse is
     * provisioned by the migration or on first use, so there is deliberately no `isFiscal` field
     * here to get wrong.
     */
    @Serializable
    data class CreateRequestDto(
        val name: String,
        val datevAccount: String,
        val closeInterval: CashBookCloseInterval = CashBookCloseInterval.MANUAL,
        val kost1: String? = null,
        val kost2: String? = null,
    ) {
        fun validate(): List<String> =
            buildList {
                if (name.isBlank() || name.length > 60) add("name must be 1..60 characters")
                if (!datevAccount.isDatevAccount()) add("datevAccount must be 1..5 digits")
            }
    }

    @Serializable
    data class UpdateRequestDto(
        val name: String,
        val datevAccount: String,
        val closeInterval: CashBookCloseInterval,
        val kost1: String? = null,
        val kost2: String? = null,
        val isActive: Boolean,
        val sortOrder: Int = 0,
    ) {
        fun validate(): List<String> =
            buildList {
                if (name.isBlank() || name.length > 60) add("name must be 1..60 characters")
                if (!datevAccount.isDatevAccount()) add("datevAccount must be 1..5 digits")
            }
    }

    @Serializable
    data class CreateTransferRequestDto(
        val fromBookId: Long,
        val toBookId: Long,
        /** Positive money amount as a decimal string, e.g. `"1200.00"`. */
        val amount: String,
        /** DD.MM.YYYY */
        val belegDatum: String,
        val note: String? = null,
    ) {
        fun validate(): List<String> =
            buildList {
                if (fromBookId == toBookId) add("fromBookId and toBookId must differ")
                if (!amount.isPositiveMoney()) add("amount must be a positive decimal string")
                if (!belegDatum.matches(DD_MM_YYYY)) add("belegDatum must be DD.MM.YYYY")
            }
    }

    @Serializable
    data class TransferResponseDto(
        val transferId: String,
        val fromLegHeaderId: Long,
        val toLegHeaderId: Long,
        val fromBelegNr: String,
        val toBelegNr: String,
        val amount: String,
    )

    @Serializable
    data class ReverseTransferRequestDto(
        val reason: String,
    ) {
        fun validate(): List<String> =
            buildList {
                if (reason.isBlank()) add("reason is required")
            }
    }

    /**
     * A read-only preview of the next Kassensturz period for a non-fiscal cash book — shown to
     * the operator before they count the safe. Creates nothing; see `CashBookCloseService.preview`.
     */
    @Serializable
    data class ClosePreviewDto(
        val cashBookId: Long,
        val cashBookName: String,
        /** ISO yyyy-MM-dd */
        val periodStart: String,
        val periodEnd: String,
        /** Money as a decimal string — never a JSON number. */
        val bookBalance: String,
    )

    /**
     * The result of a Kassensturz **close** — see `CashBookCloseService.close`.
     *
     * `differenceHeaderId` is null exactly when the operator's count matched the book: no
     * CASH_SURPLUS/CASH_SHORTAGE entry was created for a zero difference.
     */
    @Serializable
    data class CloseResponseDto(
        val cashBookId: Long,
        /** ISO yyyy-MM-dd */
        val periodStart: String,
        val periodEnd: String,
        /** Money as decimal strings — never JSON numbers. */
        val bookBalance: String,
        val countedBalance: String,
        /** `countedBalance − bookBalance`. Zero when they agree. */
        val difference: String,
        val differenceHeaderId: Long? = null,
        val closedByEmployeeId: String,
        /** ISO local date-time */
        val closedAt: String,
        val notes: String? = null,
    )

    /**
     * Records a Kassensturz — see `CashBookCloseService.close`.
     *
     * `countedBalance` may legitimately be `"0"`/`"0.00"` (an emptied safe), so unlike
     * [CreateTransferRequestDto.amount] this only rejects a negative or malformed value, never a
     * zero one.
     */
    @Serializable
    data class CloseRequestDto(
        /** Non-negative money amount as a decimal string, e.g. `"128.50"` or `"0.00"`. */
        val countedBalance: String,
        val notes: String? = null,
    ) {
        fun validate(): List<String> =
            buildList {
                if (!countedBalance.isNonNegativeMoney()) add("countedBalance must be a non-negative decimal string")
            }
    }
}

private val DD_MM_YYYY = Regex("""\d{2}\.\d{2}\.\d{4}""")

/** Up to 5 digits, matching the DATEV Sachkonto column width. */
private fun String.isDatevAccount(): Boolean = matches(Regex("""\d{1,5}"""))

/**
 * Shape-only validation of a money string: `commonMain` has no `BigDecimal`, and the server
 * re-parses this with `String.toBigDecimalOrNull()` anyway. Digits only — so a leading `-` is
 * rejected rather than silently flipping a transfer's direction — at most 4 decimals to match
 * the `DECIMAL(19,4)` column, and at least one non-zero digit so `"0"` and `"0.00"` fail.
 */
private fun String.isPositiveMoney(): Boolean = matches(Regex("""\d+(\.\d{1,4})?""")) && any { it in '1'..'9' }

/**
 * Same shape as [isPositiveMoney] but WITHOUT the non-zero-digit requirement — a counted cash
 * balance may legitimately be zero (an emptied safe). Still digits-only, so a leading `-` is
 * rejected rather than silently accepted as a negative count.
 */
private fun String.isNonNegativeMoney(): Boolean = matches(Regex("""\d+(\.\d{1,4})?"""))
