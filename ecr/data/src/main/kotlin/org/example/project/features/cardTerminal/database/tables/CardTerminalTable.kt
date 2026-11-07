package org.example.project.features.cardTerminal.database.tables

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object CardTerminalTable : LongIdTable("card_terminal") {
    val name = varchar("name", 100)
    val host = varchar("host", 255)
    val port = integer("port").default(20007)
    val password = varchar("password", 20).default("000000")
    val terminalId = varchar("terminal_id", 50).nullable()
    val softwareVersion = varchar("software_version", 50).nullable()
    val isActive = bool("is_active").default(true)
    val isConnected = bool("is_connected").default(false)
    val isBusy = bool("is_busy").default(false)
    val shopInfoId = reference("shop_info_id", ShopInfoTable)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}
