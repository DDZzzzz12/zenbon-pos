package org.example.project.features.cardTerminal.domain.service

import org.example.project.core.domain.dto.cardTerminal.CardTerminalDto

/**
 * Service interface for CardTerminal business operations.
 *
 * Defines business logic operations for card terminal management including
 * registration, connectivity control, diagnostics, and startup initialization.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 * Migrated from Result<T, Error> in Stage B Batch 9.
 */
interface CardTerminalService {
    suspend fun getAllTerminals(): List<CardTerminalDto.CardTerminalResponseDto>

    suspend fun getTerminalById(id: Long): CardTerminalDto.CardTerminalResponseDto

    suspend fun getAvailableTerminals(): List<CardTerminalDto.CardTerminalResponseDto>

    suspend fun registerTerminal(request: CardTerminalDto.CreateCardTerminalRequestDto): CardTerminalDto.CardTerminalResponseDto

    suspend fun updateTerminal(
        id: Long,
        request: CardTerminalDto.UpdateCardTerminalRequestDto,
    ): CardTerminalDto.CardTerminalResponseDto

    suspend fun deleteTerminal(id: Long)

    suspend fun connectTerminal(id: Long): CardTerminalDto.CardTerminalResponseDto

    suspend fun disconnectTerminal(id: Long): CardTerminalDto.CardTerminalResponseDto

    suspend fun getTerminalStatus(id: Long): CardTerminalDto.CardTerminalResponseDto

    suspend fun runDiagnosis(id: Long): CardTerminalDto.DiagnosisResponseDto

    suspend fun initializeOnStartup()
}
