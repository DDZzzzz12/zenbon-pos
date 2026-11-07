package org.example.project.features.bank.domain.mapper

import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.domain.models.BankStatement

/**
 * Entity → DTO. **Must be called inside a transaction**: the entity's columns are read lazily and
 * throw once the transaction has closed.
 *
 * [statementCount] and [continuityBreaks] are passed in rather than computed here — the mapper stays
 * free of queries, and the ordering the continuity check depends on is the service's business.
 */
fun BankAccount.toDto(
    statementCount: Long = 0,
    continuityBreaks: List<Long> = emptyList(),
): BankAccountDto.ResponseDto =
    BankAccountDto.ResponseDto(
        id = id.value,
        iban = iban,
        bic = bic,
        bankName = bankName,
        accountHolder = accountHolder,
        ledgerAccount = ledgerAccount,
        currency = currency,
        isActive = isActive,
        statementCount = statementCount,
        continuityBreaks = continuityBreaks,
    )

/**
 * A statement no account of ours claims. **Must be called inside a transaction.**
 *
 * [BankAccountDto.UnassignedStatementDto.assertedIban] is the bank's own text, deliberately
 * un-canonicalised: it is what an operator has to recognise before deciding which account it is.
 */
fun BankStatement.toUnassignedDto(): BankAccountDto.UnassignedStatementDto =
    BankAccountDto.UnassignedStatementDto(
        statementId = id.value,
        assertedIban = accountIban,
        bankName = bankName,
        statementDate = statementDate?.toString(),
    )
