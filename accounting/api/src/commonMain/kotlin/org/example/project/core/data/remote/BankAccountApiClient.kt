package org.example.project.core.data.remote

import org.example.project.core.domain.dto.bank.BankAccountDto

/**
 * Our own bank accounts, and the two actions that clear the Bank tab's work items. Every endpoint is
 * MANAGER-only server-side.
 */
interface BankAccountApiClient {
    /** Accounts with their statement counts and continuity gaps, plus statements awaiting an account. */
    suspend fun list(): BankAccountDto.ListResponseDto

    suspend fun get(id: Long): BankAccountDto.ResponseDto

    /**
     * One account's statements, oldest first, each flagged when it fails to continue the previous
     * one — what the account's continuity strip is drawn from.
     */
    suspend fun statements(id: Long): BankAccountDto.StatementListResponseDto

    /**
     * One page of a statement's bookings, oldest first by value date.
     *
     * [id] is the account the statement must belong to — the server answers 404 when it does not,
     * so a caller cannot read another account's bookings by passing a statement id alone.
     */
    suspend fun statementTransactions(
        id: Long,
        statementId: Long,
        limit: Int = 100,
        offset: Long = 0,
    ): BankAccountDto.StatementTransactionListResponseDto

    suspend fun create(request: BankAccountDto.CreateRequestDto): BankAccountDto.ResponseDto

    /** Re-runs IBAN → account resolution; returns how many statements it resolved. */
    suspend fun resolveUnassigned(): BankAccountDto.ResolveResponseDto

    /** Assigns one statement to an account by hand, for an IBAN resolution could not match. */
    suspend fun assignStatement(
        statementId: Long,
        request: BankAccountDto.AssignStatementRequestDto,
    ): BankAccountDto.ResponseDto
}
