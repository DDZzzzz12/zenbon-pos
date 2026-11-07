/**
 * Implementation of CardSettlementService for card terminal end-of-day settlement.
 *
 * Handles daily closing, reconciliation previews, and settlement reporting.
 * Compares terminal totals (from the ZVT End-of-Day response) against POS
 * transaction records and stores the results.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 9.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.cardTerminal.CardSettlementDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.cardTerminal.domain.models.CardSettlement
import org.example.project.features.cardTerminal.domain.models.CardSettlementDetail
import org.example.project.features.cardTerminal.domain.repository.CardSettlementRepository
import org.example.project.features.cardTerminal.domain.repository.CardTerminalRepository
import org.example.project.features.cardTerminal.domain.repository.CardTransactionRepository
import org.example.project.features.cardTerminal.domain.service.CardSettlementService
import org.example.project.features.cardTerminal.zvt.models.ZvtTransactionResult
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.slf4j.LoggerFactory

/**
 * Implementation of [CardSettlementService].
 *
 * Drives end-of-day settlement through the ZVT client, then persists
 * [CardSettlement] and [CardSettlementDetail] records. Supports
 * reconciliation by comparing terminal totals against POS transaction records.
 *
 * @property terminalService Provides access to active ZvtClient instances
 * @property terminalRepository For reading terminal metadata
 * @property transactionRepository For reading unsettled card transactions
 * @property settlementRepository For persisting settlement records
 */
class CardSettlementServiceImpl(
    private val terminalService: CardTerminalServiceImpl,
    private val terminalRepository: CardTerminalRepository,
    private val transactionRepository: CardTransactionRepository,
    private val settlementRepository: CardSettlementRepository,
) : CardSettlementService {
    private val logger = LoggerFactory.getLogger(CardSettlementServiceImpl::class.java)

    override suspend fun previewTotals(terminalId: Long): CardSettlementDto.SettlementPreviewDto {
        val client = terminalService.getClient(terminalId) ?: throw cardTerminalDisconnected(terminalId)

        val terminalName =
            dbQuery {
                terminalRepository.getById(terminalId)?.name ?: "Unknown"
            }

        // Best-effort: a print-totals failure is logged and yields an empty list.
        val terminalTotals =
            try {
                parseTerminalTotals(client.printTotals())
            } catch (e: PosException) {
                logger.warn("PrintTotals failed for terminal $terminalId: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                logger.warn("PrintTotals failed for terminal $terminalId: ${e.message}")
                emptyList()
            }

        val posTotals =
            dbQuery {
                val lastSettlement = settlementRepository.getLastSettlementTime(terminalId)
                val txs = transactionRepository.getByTerminalIdSinceLastSettlement(terminalId, lastSettlement)
                txs
                    .groupBy { Pair(it.cardType ?: 0, it.cardTypeName ?: "Unknown") }
                    .map { (key, group) ->
                        CardSettlementDto.CardTypeTotalsDto(
                            cardType = key.first,
                            cardTypeName = key.second,
                            transactionCount = group.size,
                            totalAmountCents = group.sumOf { it.amountCents },
                        )
                    }
            }

        val discrepancies = findDiscrepancies(terminalTotals, posTotals)
        val status = if (discrepancies.isEmpty()) "OK" else "DISCREPANCY"

        return CardSettlementDto.SettlementPreviewDto(
            terminalId = terminalId,
            terminalName = terminalName,
            terminalTotals = terminalTotals,
            posTotals = posTotals,
            discrepancies = discrepancies,
            status = status,
        )
    }

    override suspend fun executeSettlement(
        terminalId: Long,
        zbonId: Long?,
    ): CardSettlementDto.CardSettlementResponseDto {
        val client = terminalService.getClient(terminalId) ?: throw cardTerminalDisconnected(terminalId)

        val now = currentTimeStamp()

        val r =
            try {
                client.endOfDay()
            } catch (e: Exception) {
                // Store a failed settlement record so history is preserved.
                try {
                    dbQuery {
                        settlementRepository.create(
                            cardTerminalId = terminalId,
                            zbonId = zbonId,
                            settlementTime = now,
                            success = false,
                            resultCode = -1,
                            receiptText = null,
                            totalAmountCents = 0,
                            totalTransactionCount = 0,
                        )
                    }
                } catch (storeErr: Exception) {
                    logger.warn("Failed to store failed settlement record: ${storeErr.message}")
                }
                logger.error("Settlement failed for terminal $terminalId: ${e.message}", e)
                throw cardSettlementFailed(e.message ?: "ZVT end-of-day failure", cause = e)
            }

        val totals = parseTerminalTotals(r)
        return dbQuery {
            val settlement =
                settlementRepository.create(
                    cardTerminalId = terminalId,
                    zbonId = zbonId,
                    settlementTime = now,
                    success = r.approved,
                    resultCode = r.resultCode,
                    receiptText = r.receiptText.joinToString("\n").takeIf { it.isNotBlank() },
                    totalAmountCents = r.amountCents,
                    totalTransactionCount = totals.sumOf { it.transactionCount },
                )
            totals.forEach { total ->
                settlementRepository.createDetail(
                    cardSettlementId = settlement.id.value,
                    cardType = total.cardType,
                    cardTypeName = total.cardTypeName,
                    transactionCount = total.transactionCount,
                    totalAmountCents = total.totalAmountCents,
                )
            }
            settlement.toResponse()
        }
    }

    override suspend fun getSettlementsByZbon(zbonId: Long): List<CardSettlementDto.CardSettlementResponseDto> =
        dbQuery {
            settlementRepository.getByZbonId(zbonId).map { it.toResponse() }
        }

    override suspend fun getReconciliationReport(zbonId: Long): CardSettlementDto.ReconciliationReportDto =
        dbQuery {
            val settlements = settlementRepository.getByZbonId(zbonId)
            val dtos = settlements.map { it.toResponse() }
            val totalAmount = settlements.sumOf { it.totalAmountCents }
            val totalCount = settlements.sumOf { it.totalTransactionCount }
            val allSuccessful = settlements.all { it.success }
            CardSettlementDto.ReconciliationReportDto(
                zbonId = zbonId,
                settlements = dtos,
                totalAmountCents = totalAmount,
                totalTransactionCount = totalCount,
                allSuccessful = allSuccessful,
            )
        }

    // ===== Helpers =====

    /**
     * Parses terminal totals from a ZVT transaction result.
     *
     * Terminal firmware varies widely in how per-card-type totals are reported.
     * This base implementation creates a single entry if an amount is present.
     * Override or extend for terminals that report per-card-type breakdowns.
     */
    private fun parseTerminalTotals(result: ZvtTransactionResult): List<CardSettlementDto.CardTypeTotalsDto> {
        if (result.amountCents <= 0) return emptyList()
        return listOf(
            CardSettlementDto.CardTypeTotalsDto(
                cardType = result.cardType ?: 0,
                cardTypeName = result.cardTypeName ?: "Unknown",
                transactionCount = 1,
                totalAmountCents = result.amountCents,
            ),
        )
    }

    /**
     * Compares terminal totals against POS totals to find discrepancies.
     *
     * Matches entries by card type name. Any entry present in one set
     * but not the other, or with differing counts/amounts, is reported.
     */
    private fun findDiscrepancies(
        terminalTotals: List<CardSettlementDto.CardTypeTotalsDto>,
        posTotals: List<CardSettlementDto.CardTypeTotalsDto>,
    ): List<CardSettlementDto.DiscrepancyDto> {
        val allCardTypeNames = (terminalTotals.map { it.cardTypeName } + posTotals.map { it.cardTypeName }).toSet()
        return allCardTypeNames.mapNotNull { name ->
            val terminalEntry = terminalTotals.find { it.cardTypeName == name }
            val posEntry = posTotals.find { it.cardTypeName == name }
            val tCount = terminalEntry?.transactionCount ?: 0
            val pCount = posEntry?.transactionCount ?: 0
            val tAmount = terminalEntry?.totalAmountCents ?: 0L
            val pAmount = posEntry?.totalAmountCents ?: 0L
            if (tCount != pCount || tAmount != pAmount) {
                CardSettlementDto.DiscrepancyDto(
                    cardTypeName = name,
                    terminalCount = tCount,
                    posCount = pCount,
                    terminalAmountCents = tAmount,
                    posAmountCents = pAmount,
                )
            } else {
                null
            }
        }
    }

    // ===== Mapping =====

    private fun CardSettlement.toResponse(): CardSettlementDto.CardSettlementResponseDto =
        CardSettlementDto.CardSettlementResponseDto(
            id = id.value,
            cardTerminalId = cardTerminal.id.value,
            zbonId = zbon?.id?.value,
            settlementTime = settlementTime.toString(),
            success = success,
            resultCode = resultCode,
            receiptText = receiptText,
            totalAmountCents = totalAmountCents,
            totalTransactionCount = totalTransactionCount,
            details = details.map { it.toDetailDto() },
            createdAt = createdAt.toString(),
        )

    private fun CardSettlementDetail.toDetailDto(): CardSettlementDto.CardSettlementDetailDto =
        CardSettlementDto.CardSettlementDetailDto(
            id = id.value,
            cardSettlementId = cardSettlement.id.value,
            cardType = cardType,
            cardTypeName = cardTypeName,
            transactionCount = transactionCount,
            totalAmountCents = totalAmountCents,
        )

    // ===== PosException helpers =====

    private fun cardTerminalDisconnected(id: Long) =
        PosException(
            PosErrorCode.CARD_TERMINAL_DISCONNECTED,
            HttpStatusCode.ServiceUnavailable,
            "Card terminal $id disconnected",
        )

    private fun cardSettlementFailed(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.CARD_SETTLEMENT_FAILED,
        HttpStatusCode.InternalServerError,
        "Card settlement failed: $msg",
        cause = cause,
    )
}
