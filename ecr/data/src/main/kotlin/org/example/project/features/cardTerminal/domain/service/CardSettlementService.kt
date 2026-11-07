package org.example.project.features.cardTerminal.domain.service

import org.example.project.core.domain.dto.cardTerminal.CardSettlementDto

/**
 * Service interface for card terminal end-of-day settlement operations.
 *
 * Defines business logic operations for executing and reconciling card terminal
 * settlements, including preview, execution, and zBon-scoped reporting.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 * Migrated from Result<T, Error> in Stage B Batch 9.
 */
interface CardSettlementService {
    suspend fun previewTotals(terminalId: Long): CardSettlementDto.SettlementPreviewDto

    suspend fun executeSettlement(
        terminalId: Long,
        zbonId: Long?,
    ): CardSettlementDto.CardSettlementResponseDto

    suspend fun getSettlementsByZbon(zbonId: Long): List<CardSettlementDto.CardSettlementResponseDto>

    suspend fun getReconciliationReport(zbonId: Long): CardSettlementDto.ReconciliationReportDto
}
