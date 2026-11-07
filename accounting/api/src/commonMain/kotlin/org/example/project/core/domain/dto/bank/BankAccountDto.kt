package org.example.project.core.domain.dto.bank

import kotlinx.serialization.Serializable

/**
 * Wire contract for our own bank accounts.
 *
 * No money field: balances belong to a statement, not an account. `unassignedStatements` is a count,
 * not an amount.
 */
object BankAccountDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val iban: String,
        val bic: String? = null,
        val bankName: String? = null,
        val accountHolder: String? = null,
        val ledgerAccount: String? = null,
        val currency: String = "EUR",
        val isActive: Boolean = true,
        val statementCount: Long = 0,
        /** Statement ids whose opening balance does not continue the previous closing balance. */
        val continuityBreaks: List<Long> = emptyList(),
    )

    @Serializable
    data class ListResponseDto(
        val items: List<ResponseDto>,
        /** Statements parsed but not yet assigned to any account — the Bank tab's work item. */
        val unassigned: List<UnassignedStatementDto> = emptyList(),
    )

    /**
     * One statement held for an account, for the account's own statement list.
     *
     * Money is a string, not a number — every amount on the wire is (`platform/money/README.md`).
     *
     * [continuityBreak] is the property this DTO exists for: it says that *this* statement's opening
     * balance does not continue the previous one's closing balance, so a statement is missing
     * between them. [BankAccountDto.ResponseDto.continuityBreaks] carries the same information as a
     * list of bare statement ids, which a client can render only as a row of database keys.
     */
    @Serializable
    data class StatementDto(
        val statementId: Long,
        val statementNumber: String? = null,
        val statementDate: String? = null,
        val periodStart: String? = null,
        val periodEnd: String? = null,
        val openingBalance: String? = null,
        val closingBalance: String? = null,
        val currency: String = "EUR",
        val transactionCount: Long = 0,
        /** The archived file this was parsed from, when there is one; null for a hand-entered row. */
        val sourceDocumentId: Long? = null,
        val continuityBreak: Boolean = false,
        /**
         * The earliest already-stored statement of this account whose period overlaps this one's,
         * or null. Written at import by `StatementDuplicateMarker`; the repeated bookings themselves
         * carry [StatementTransactionDto.duplicateOfTransactionId]. A hint, never a deletion.
         */
        val overlappingStatementId: Long? = null,
    )

    /** An account's statements in period order, oldest first — the order the gaps are computed in. */
    @Serializable
    data class StatementListResponseDto(
        val bankAccountId: Long,
        val iban: String,
        val items: List<StatementDto> = emptyList(),
    )

    /**
     * One booking on a statement.
     *
     * Deliberately **not** `DocumentDto.BankStatementTransactionDto`, which the Belege detail page
     * renders: that one carries neither the transaction id nor a residual, and both are the point
     * here — the id links the row into Zuordnung, the residual is what makes it say whether the
     * money is still waiting to be assigned.
     *
     * [amount] is always positive; the direction is [creditDebit], matching the column. [openAmount]
     * is the **effective** residual — the stored `open_amount` when it has one, else the full amount,
     * because a NULL there means nobody has computed one yet, not that the booking is settled.
     */
    @Serializable
    data class StatementTransactionDto(
        val transactionId: Long,
        val bookingDate: String? = null,
        val valueDate: String? = null,
        val amount: String,
        val openAmount: String,
        val currency: String = "EUR",
        val creditDebit: String,
        val counterpartyName: String? = null,
        val counterpartyIban: String? = null,
        val remittance: String? = null,
        /** Set when an overlapping statement re-delivered this booking. A hint, never a deletion. */
        val duplicateOfTransactionId: Long? = null,
    )

    /**
     * One page of a statement's bookings, oldest first.
     *
     * [total] is the statement's whole count, not the page's, so a client can say "100 von 214"
     * rather than leaving the operator to guess whether the list ended or was cut off.
     */
    @Serializable
    data class StatementTransactionListResponseDto(
        val statementId: Long,
        val items: List<StatementTransactionDto> = emptyList(),
        val total: Long = 0,
        val offset: Long = 0,
    )

    /** A statement `:accounting-banking` stored whose IBAN matches no account of ours. */
    @Serializable
    data class UnassignedStatementDto(
        val statementId: Long,
        val assertedIban: String? = null,
        val bankName: String? = null,
        val statementDate: String? = null,
    )

    @Serializable
    data class CreateRequestDto(
        val iban: String,
        val bic: String? = null,
        val bankName: String? = null,
        val accountHolder: String? = null,
        val ledgerAccount: String? = null,
        val currency: String = "EUR",
    ) {
        fun validate(): List<String> = if (iban.isBlank()) listOf("iban") else emptyList()
    }

    @Serializable
    data class AssignStatementRequestDto(
        val bankAccountId: Long,
    )

    /** How many statements one resolution sweep claimed. */
    @Serializable
    data class ResolveResponseDto(
        val resolved: Int,
    )
}
