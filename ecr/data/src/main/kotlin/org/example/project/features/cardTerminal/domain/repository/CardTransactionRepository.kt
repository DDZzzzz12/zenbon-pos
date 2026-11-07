package org.example.project.features.cardTerminal.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.cardTerminal.domain.models.CardTransaction

/**
 * Repository interface for CardTransaction data operations.
 *
 * Defines the contract for card payment transaction data access.
 * Returns plain entities or nullable types; exceptions propagate to the service layer.
 */
interface CardTransactionRepository {
    fun getById(id: Long): CardTransaction?

    fun getByPaymentId(paymentId: Long): CardTransaction?

    fun getByTerminalId(terminalId: Long): List<CardTransaction>

    fun getByTerminalIdSinceLastSettlement(
        terminalId: Long,
        lastSettlementTime: LocalDateTime?,
    ): List<CardTransaction>

    fun create(
        cardTerminalId: Long,
        traceNumber: String,
        receiptNumber: String?,
        amountCents: Long,
        currency: String,
        cardType: Int?,
        cardTypeName: String?,
        panLast4: String?,
        expiryDate: String?,
        aid: String?,
        paymentType: String,
        resultCode: Int,
        receiptTextMerchant: String?,
        receiptTextCustomer: String?,
        transactionTime: LocalDateTime,
    ): CardTransaction

    fun linkToPayment(
        cardTransactionId: Long,
        paymentId: Long,
    )
}
