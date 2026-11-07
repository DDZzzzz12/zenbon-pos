package org.example.project.admin.features.bank

import org.example.project.core.data.remote.BankAccountApiClient
import org.example.project.core.domain.dto.bank.BankAccountDto

/** Records calls and replays canned answers; [assignments] and [resolveCalls] are what tests assert on. */
class FakeBankAccountApiClient : BankAccountApiClient {
    var accounts: List<BankAccountDto.ResponseDto> = emptyList()
    var unassigned: List<BankAccountDto.UnassignedStatementDto> = emptyList()
    var resolvedCount: Int = 0
    var failWith: Throwable? = null

    var listCalls = 0
    val creates = mutableListOf<BankAccountDto.CreateRequestDto>()
    val assignments = mutableListOf<Pair<Long, Long>>()
    var resolveCalls = 0
    var statements: List<BankAccountDto.StatementDto> = emptyList()
    val statementCalls = mutableListOf<Long>()

    /** Bookings per statement id. A statement absent from the map answers with an empty page. */
    var transactionsByStatement: Map<Long, List<BankAccountDto.StatementTransactionDto>> = emptyMap()

    /** Every (accountId, statementId) asked for, in order — how the caching tests count fetches. */
    val transactionCalls = mutableListOf<Pair<Long, Long>>()

    /** Reported instead of the page size, so a test can pretend the statement holds more. */
    var transactionTotalOverride: Long? = null

    override suspend fun list(): BankAccountDto.ListResponseDto {
        listCalls++
        failWith?.let { throw it }
        return BankAccountDto.ListResponseDto(items = accounts, unassigned = unassigned)
    }

    override suspend fun get(id: Long): BankAccountDto.ResponseDto {
        failWith?.let { throw it }
        return accounts.first { it.id == id }
    }

    override suspend fun statements(id: Long): BankAccountDto.StatementListResponseDto {
        statementCalls += id
        failWith?.let { throw it }
        return BankAccountDto.StatementListResponseDto(
            bankAccountId = id,
            iban = accounts.firstOrNull { it.id == id }?.iban.orEmpty(),
            items = statements,
        )
    }

    override suspend fun statementTransactions(
        id: Long,
        statementId: Long,
        limit: Int,
        offset: Long,
    ): BankAccountDto.StatementTransactionListResponseDto {
        transactionCalls += id to statementId
        failWith?.let { throw it }
        val all = transactionsByStatement[statementId].orEmpty()
        val page = all.drop(offset.toInt()).take(limit)
        return BankAccountDto.StatementTransactionListResponseDto(
            statementId = statementId,
            items = page,
            total = transactionTotalOverride ?: all.size.toLong(),
            offset = offset,
        )
    }

    override suspend fun create(request: BankAccountDto.CreateRequestDto): BankAccountDto.ResponseDto {
        creates += request
        failWith?.let { throw it }
        return BankAccountDto.ResponseDto(id = 99, iban = request.iban, bankName = request.bankName)
    }

    override suspend fun resolveUnassigned(): BankAccountDto.ResolveResponseDto {
        resolveCalls++
        failWith?.let { throw it }
        return BankAccountDto.ResolveResponseDto(resolved = resolvedCount)
    }

    override suspend fun assignStatement(
        statementId: Long,
        request: BankAccountDto.AssignStatementRequestDto,
    ): BankAccountDto.ResponseDto {
        assignments += statementId to request.bankAccountId
        failWith?.let { throw it }
        return accounts.first { it.id == request.bankAccountId }
    }
}
