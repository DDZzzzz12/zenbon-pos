package org.example.project.features.cardTerminal.domain.models

import org.example.project.features.cardTerminal.database.tables.CardTerminalTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class CardTerminal(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<CardTerminal>(CardTerminalTable)

    var name by CardTerminalTable.name
    var host by CardTerminalTable.host
    var port by CardTerminalTable.port
    var password by CardTerminalTable.password
    var terminalId by CardTerminalTable.terminalId
    var softwareVersion by CardTerminalTable.softwareVersion
    var isActive by CardTerminalTable.isActive
    var isConnected by CardTerminalTable.isConnected
    var isBusy by CardTerminalTable.isBusy
    var shopInfo by ShopInfo referencedOn CardTerminalTable.shopInfoId
    var createdAt by CardTerminalTable.createdAt
    var updatedAt by CardTerminalTable.updatedAt
}
