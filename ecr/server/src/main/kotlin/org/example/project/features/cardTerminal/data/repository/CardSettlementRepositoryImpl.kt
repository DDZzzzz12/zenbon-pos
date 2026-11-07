package org.example.project.features.cardTerminal.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.cardTerminal.database.tables.CardSettlementTable
import org.example.project.features.cardTerminal.domain.models.CardSettlement
import org.example.project.features.cardTerminal.domain.models.CardSettlementDetail
import org.example.project.features.cardTerminal.domain.models.CardTerminal
import org.example.project.features.cardTerminal.domain.repository.CardSettlementRepository
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Implementation of CardSettlementRepository using Exposed ORM.
 *
 * Provides data access operations for CardSettlement and CardSettlementDetail entities.
 * Returns plain entities; exceptions propagate naturally to the service layer.
 */
class CardSettlementRepositoryImpl : CardSettlementRepository {
    override fun getById(id: Long): CardSettlement? = CardSettlement.findById(id)

    override fun getByZbonId(zbonId: Long): List<CardSettlement> = CardSettlement.find { CardSettlementTable.zbonId eq zbonId }.toList()

    override fun getByTerminalId(terminalId: Long): List<CardSettlement> =
        CardSettlement
            .find {
                CardSettlementTable.cardTerminalId eq terminalId
            }.toList()

    override fun getLastSettlementTime(terminalId: Long): LocalDateTime? =
        CardSettlement
            .find {
                (CardSettlementTable.cardTerminalId eq terminalId) and
                    (CardSettlementTable.success eq true)
            }.orderBy(CardSettlementTable.settlementTime to SortOrder.DESC)
            .firstOrNull()
            ?.settlementTime

    override fun create(
        cardTerminalId: Long,
        zbonId: Long?,
        settlementTime: LocalDateTime,
        success: Boolean,
        resultCode: Int,
        receiptText: String?,
        totalAmountCents: Long,
        totalTransactionCount: Int,
    ): CardSettlement {
        val terminal =
            CardTerminal.findById(cardTerminalId)
                ?: throw NoSuchElementException("CardTerminal not found: $cardTerminalId")
        val zbon =
            zbonId?.let {
                ZBon.findById(it) ?: throw NoSuchElementException("ZBon not found: $it")
            }
        return CardSettlement.new {
            this.cardTerminal = terminal
            this.zbon = zbon
            this.settlementTime = settlementTime
            this.success = success
            this.resultCode = resultCode
            this.receiptText = receiptText
            this.totalAmountCents = totalAmountCents
            this.totalTransactionCount = totalTransactionCount
            this.createdAt = currentTimeStamp()
        }
    }

    override fun createDetail(
        cardSettlementId: Long,
        cardType: Int,
        cardTypeName: String,
        transactionCount: Int,
        totalAmountCents: Long,
    ) {
        val settlement =
            CardSettlement.findById(cardSettlementId)
                ?: throw NoSuchElementException("CardSettlement not found: $cardSettlementId")
        CardSettlementDetail.new {
            this.cardSettlement = settlement
            this.cardType = cardType
            this.cardTypeName = cardTypeName
            this.transactionCount = transactionCount
            this.totalAmountCents = totalAmountCents
        }
    }
}
