package org.example.project.core.domain.dto.cardTerminal

import kotlinx.serialization.Serializable

/**
 * Container object for CardSettlement-related DTOs.
 *
 * Organizes DTOs for end-of-day card terminal settlement,
 * reconciliation preview, and reporting.
 */
object CardSettlementDto {
    @Serializable
    data class CardSettlementDetailDto(
        val id: Long,
        val cardSettlementId: Long,
        val cardType: Int,
        val cardTypeName: String,
        val transactionCount: Int,
        val totalAmountCents: Long,
    )

    @Serializable
    data class CardSettlementResponseDto(
        val id: Long,
        val cardTerminalId: Long,
        val zbonId: Long?,
        val settlementTime: String, // LocalDateTime serialized as String
        val success: Boolean,
        val resultCode: Int,
        val receiptText: String?,
        val totalAmountCents: Long,
        val totalTransactionCount: Int,
        val details: List<CardSettlementDetailDto>,
        val createdAt: String, // LocalDateTime serialized as String
    )

    @Serializable
    data class CardTypeTotalsDto(
        val cardType: Int,
        val cardTypeName: String,
        val transactionCount: Int,
        val totalAmountCents: Long,
    )

    @Serializable
    data class DiscrepancyDto(
        val cardTypeName: String,
        val terminalCount: Int,
        val posCount: Int,
        val terminalAmountCents: Long,
        val posAmountCents: Long,
    )

    @Serializable
    data class SettlementPreviewDto(
        val terminalId: Long,
        val terminalName: String,
        val terminalTotals: List<CardTypeTotalsDto>,
        val posTotals: List<CardTypeTotalsDto>,
        val discrepancies: List<DiscrepancyDto>,
        val status: String, // e.g. "OK", "DISCREPANCY"
    )

    @Serializable
    data class ReconciliationReportDto(
        val zbonId: Long,
        val settlements: List<CardSettlementResponseDto>,
        val totalAmountCents: Long,
        val totalTransactionCount: Int,
        val allSuccessful: Boolean,
    )
}
