package org.example.project.features.cardTerminal.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.cardTerminal.domain.models.CardSettlement

/**
 * Repository interface for CardSettlement data operations.
 *
 * Defines the contract for end-of-day settlement data access.
 * Returns plain entities or nullable types; exceptions propagate to the service layer.
 */
interface CardSettlementRepository {
    fun getById(id: Long): CardSettlement?

    fun getByZbonId(zbonId: Long): List<CardSettlement>

    fun getByTerminalId(terminalId: Long): List<CardSettlement>

    fun getLastSettlementTime(terminalId: Long): LocalDateTime?

    fun create(
        cardTerminalId: Long,
        zbonId: Long?,
        settlementTime: LocalDateTime,
        success: Boolean,
        resultCode: Int,
        receiptText: String?,
        totalAmountCents: Long,
        totalTransactionCount: Int,
    ): CardSettlement

    fun createDetail(
        cardSettlementId: Long,
        cardType: Int,
        cardTypeName: String,
        transactionCount: Int,
        totalAmountCents: Long,
    )
}
