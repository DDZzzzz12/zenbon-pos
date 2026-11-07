package org.example.project.features.cardTerminal.data.repository

import org.example.project.features.cardTerminal.database.tables.CardTerminalTable
import org.example.project.features.cardTerminal.domain.models.CardTerminal
import org.example.project.features.cardTerminal.domain.repository.CardTerminalRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Implementation of CardTerminalRepository using Exposed ORM.
 *
 * Provides data access operations for CardTerminal entities. Returns plain entities;
 * exceptions propagate naturally to the service layer.
 */
class CardTerminalRepositoryImpl : CardTerminalRepository {
    override fun getAll(): List<CardTerminal> = CardTerminal.all().toList()

    override fun getById(id: Long): CardTerminal? = CardTerminal.findById(id)

    override fun getActiveTerminals(): List<CardTerminal> = CardTerminal.find { CardTerminalTable.isActive eq true }.toList()

    override fun create(
        name: String,
        host: String,
        port: Int,
        password: String,
        shopInfoId: Long,
    ): CardTerminal {
        val shopInfo =
            ShopInfo.findById(shopInfoId)
                ?: throw NoSuchElementException("ShopInfo not found: $shopInfoId")
        val timestamp = currentTimeStamp()
        return CardTerminal.new {
            this.name = name
            this.host = host
            this.port = port
            this.password = password
            this.shopInfo = shopInfo
            this.isActive = true
            this.isConnected = false
            this.isBusy = false
            this.createdAt = timestamp
            this.updatedAt = timestamp
        }
    }

    override fun update(
        id: Long,
        name: String?,
        host: String?,
        port: Int?,
        password: String?,
    ): CardTerminal? {
        val terminal = CardTerminal.findById(id) ?: return null
        name?.let { terminal.name = it }
        host?.let { terminal.host = it }
        port?.let { terminal.port = it }
        password?.let { terminal.password = it }
        terminal.updatedAt = currentTimeStamp()
        return terminal
    }

    override fun delete(id: Long): Boolean {
        val terminal = CardTerminal.findById(id) ?: return false
        terminal.isActive = false
        terminal.updatedAt = currentTimeStamp()
        return true
    }

    override fun updateConnectionState(
        id: Long,
        isConnected: Boolean,
    ) {
        val terminal =
            CardTerminal.findById(id)
                ?: throw NoSuchElementException("CardTerminal not found: $id")
        terminal.isConnected = isConnected
        terminal.updatedAt = currentTimeStamp()
    }

    override fun updateBusyState(
        id: Long,
        isBusy: Boolean,
    ) {
        val terminal =
            CardTerminal.findById(id)
                ?: throw NoSuchElementException("CardTerminal not found: $id")
        terminal.isBusy = isBusy
        terminal.updatedAt = currentTimeStamp()
    }

    override fun updateTerminalInfo(
        id: Long,
        terminalId: String?,
        softwareVersion: String?,
    ) {
        val terminal =
            CardTerminal.findById(id)
                ?: throw NoSuchElementException("CardTerminal not found: $id")
        terminalId?.let { terminal.terminalId = it }
        softwareVersion?.let { terminal.softwareVersion = it }
        terminal.updatedAt = currentTimeStamp()
    }

    override fun resetRuntimeState() {
        CardTerminal.all().forEach { terminal ->
            terminal.isConnected = false
            terminal.isBusy = false
        }
    }
}
