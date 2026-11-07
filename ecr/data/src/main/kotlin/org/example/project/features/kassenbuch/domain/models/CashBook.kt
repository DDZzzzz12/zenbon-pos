package org.example.project.features.kassenbuch.domain.models

import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.models.Terminal
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class CashBook(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<CashBook>(CashBookTable)

    var shop by ShopInfo referencedOn CashBookTable.shopId
    var name by CashBookTable.name
    var isFiscal by CashBookTable.isFiscal
    var datevAccount by CashBookTable.datevAccount
    var kost1 by CashBookTable.kost1
    var kost2 by CashBookTable.kost2
    var closeInterval by CashBookTable.closeInterval
    var isActive by CashBookTable.isActive
    var sortOrder by CashBookTable.sortOrder
    var signingTerminal by Terminal optionalReferencedOn CashBookTable.signingTerminalId
    var createdAt by CashBookTable.createdAt
}
