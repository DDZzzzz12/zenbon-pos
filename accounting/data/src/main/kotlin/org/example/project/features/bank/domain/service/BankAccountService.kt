package org.example.project.features.bank.domain.service

import org.example.project.core.domain.dto.bank.BankAccountDto

/**
 * Our own bank accounts, as the HTTP layer sees them.
 *
 * Returns DTOs and throws `PosException` on error, per the server's service-layer contract. The
 * entity-to-DTO mapping happens inside the service's own `dbQuery`, because the statement counts and
 * balances it reads are lazy Exposed queries that are unavailable once the transaction closes.
 */
interface BankAccountService {
    /** Active accounts, each with its statement count and continuity breaks, plus unassigned statements. */
    suspend fun list(): BankAccountDto.ListResponseDto

    suspend fun get(id: Long): BankAccountDto.ResponseDto

    suspend fun create(request: BankAccountDto.CreateRequestDto): BankAccountDto.ResponseDto

    /** Assigns a parsed statement to an account by hand, for an IBAN resolution could not match. */
    suspend fun assignStatement(
        statementId: Long,
        request: BankAccountDto.AssignStatementRequestDto,
    ): BankAccountDto.ResponseDto

    /** Re-runs resolution over unassigned statements; returns how many were resolved. */
    suspend fun resolveUnassigned(): Int

    /**
     * One account's statements, oldest first, each flagged when it fails to continue the previous
     * one's closing balance.
     *
     * The list endpoint reports the same gaps as `continuityBreaks` — a list of bare statement ids,
     * which a client can render only as database keys. Here the flag sits on the statement beside
     * the period it covers, which is what a continuity strip is drawn from.
     *
     * Throws a 404 `PosException` for an unknown account. An account holding no statements is an
     * empty list, not an error.
     */
    suspend fun statementsFor(bankAccountId: Long): BankAccountDto.StatementListResponseDto

    /**
     * One page of a statement's bookings, oldest first.
     *
     * [bankAccountId] is not decoration: the statement must belong to it, or a caller holding
     * `CREDITOR_READ` could read any account's bookings by guessing a statement id. A statement that
     * exists but hangs off another account is answered 404 — the same as one that does not exist —
     * because distinguishing the two would confirm the id is real.
     *
     * Paged rather than whole: a monthly CAMT file can carry several hundred bookings, and the
     * expanded row that shows them sits inside a card, not on a screen of its own.
     */
    suspend fun statementTransactions(
        bankAccountId: Long,
        statementId: Long,
        limit: Int,
        offset: Long,
    ): BankAccountDto.StatementTransactionListResponseDto
}
