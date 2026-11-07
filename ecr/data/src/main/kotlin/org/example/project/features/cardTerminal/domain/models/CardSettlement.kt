package org.example.project.features.cardTerminal.domain.models

import org.example.project.features.cardTerminal.database.tables.CardSettlementDetailTable
import org.example.project.features.cardTerminal.database.tables.CardSettlementTable
import org.example.project.features.zBon.domain.models.ZBon
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class CardSettlement(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<CardSettlement>(CardSettlementTable)

    var cardTerminal by CardTerminal referencedOn CardSettlementTable.cardTerminalId
    var zbon by ZBon optionalReferencedOn CardSettlementTable.zbonId
    var settlementTime by CardSettlementTable.settlementTime
    var success by CardSettlementTable.success
    var resultCode by CardSettlementTable.resultCode
    var receiptText by CardSettlementTable.receiptText
    var totalAmountCents by CardSettlementTable.totalAmountCents
    var totalTransactionCount by CardSettlementTable.totalTransactionCount
    var createdAt by CardSettlementTable.createdAt
    val details by CardSettlementDetail referrersOn CardSettlementDetailTable.cardSettlementId
}
