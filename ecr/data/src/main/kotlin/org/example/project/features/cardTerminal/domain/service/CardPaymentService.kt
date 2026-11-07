package org.example.project.features.cardTerminal.domain.service

import kotlinx.coroutines.flow.Flow
import org.example.project.core.domain.dto.cardTerminal.CardTransactionDto

/**
 * Service interface for card payment authorization operations.
 *
 * Defines business logic operations for initiating and managing card payments
 * through a ZVT-compatible card terminal, including authorization, abort,
 * intermediate status streaming, and linking to POS payments.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors.
 * Migrated from Result<T, Error> in Stage B Batch 9.
 */
interface CardPaymentService {
    suspend fun authorizePayment(
        terminalId: Long,
        amountCents: Long,
        currency: String,
    ): CardTransactionDto.CardTransactionResponseDto

    suspend fun linkTransactionToPayment(
        cardTransactionId: Long,
        paymentId: Long,
    )

    fun getIntermediateStatusFlow(terminalId: Long): Flow<Pair<Int, String>>

    suspend fun abortPayment(terminalId: Long)
}
