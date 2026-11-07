package org.example.project.features.cardTerminal.domain.repository

import org.example.project.features.cardTerminal.domain.models.CardTerminal

/**
 * Repository interface for CardTerminal data operations.
 *
 * Defines the contract for card terminal data access. Returns plain entities
 * or nullable types; exceptions propagate naturally to the service layer.
 */
interface CardTerminalRepository {
    fun getAll(): List<CardTerminal>

    fun getById(id: Long): CardTerminal?

    fun getActiveTerminals(): List<CardTerminal>

    fun create(
        name: String,
        host: String,
        port: Int,
        password: String,
        shopInfoId: Long,
    ): CardTerminal

    fun update(
        id: Long,
        name: String?,
        host: String?,
        port: Int?,
        password: String?,
    ): CardTerminal?

    fun delete(id: Long): Boolean

    fun updateConnectionState(
        id: Long,
        isConnected: Boolean,
    )

    fun updateBusyState(
        id: Long,
        isBusy: Boolean,
    )

    fun updateTerminalInfo(
        id: Long,
        terminalId: String?,
        softwareVersion: String?,
    )

    fun resetRuntimeState()
}
