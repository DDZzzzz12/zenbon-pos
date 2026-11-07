package org.example.project.features.cardTerminal.domain.models

import org.example.project.features.cardTerminal.database.tables.CardSettlementDetailTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class CardSettlementDetail(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<CardSettlementDetail>(CardSettlementDetailTable)

    var cardSettlement by CardSettlement referencedOn CardSettlementDetailTable.cardSettlementId
    var cardType by CardSettlementDetailTable.cardType
    var cardTypeName by CardSettlementDetailTable.cardTypeName
    var transactionCount by CardSettlementDetailTable.transactionCount
    var totalAmountCents by CardSettlementDetailTable.totalAmountCents
}
