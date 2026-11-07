package org.example.project.features.cardTerminal.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.cardTerminal.database.tables.CardTransactionTable
import org.example.project.features.cardTerminal.domain.models.CardTerminal
import org.example.project.features.cardTerminal.domain.models.CardTransaction
import org.example.project.features.cardTerminal.domain.repository.CardTransactionRepository
import org.example.project.features.payment.domain.models.Payment
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Implementation of CardTransactionRepository using Exposed ORM.
 *
 * Provides data access operations for CardTransaction entities. Returns plain entities;
 * exceptions propagate naturally to the service layer.
 */
class CardTransactionRepositoryImpl : CardTransactionRepository {
    override fun getById(id: Long): CardTransaction? = CardTransaction.findById(id)

    override fun getByPaymentId(paymentId: Long): CardTransaction? =
        CardTransaction
            .find {
                CardTransactionTable.paymentId eq paymentId
            }.firstOrNull()

    override fun getByTerminalId(terminalId: Long): List<CardTransaction> =
        CardTransaction
            .find {
                CardTransactionTable.cardTerminalId eq terminalId
            }.toList()

    override fun getByTerminalIdSinceLastSettlement(
        terminalId: Long,
        lastSettlementTime: LocalDateTime?,
    ): List<CardTransaction> =
        if (lastSettlementTime != null) {
            CardTransaction
                .find {
                    (CardTransactionTable.cardTerminalId eq terminalId) and
                        (CardTransactionTable.transactionTime greater lastSettlementTime)
                }.toList()
        } else {
            CardTransaction.find { CardTransactionTable.cardTerminalId eq terminalId }.toList()
        }

    override fun create(
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
    ): CardTransaction {
        val terminal =
            CardTerminal.findById(cardTerminalId)
                ?: throw NoSuchElementException("CardTerminal not found: $cardTerminalId")
        return CardTransaction.new {
            this.cardTerminal = terminal
            this.traceNumber = traceNumber
            this.receiptNumber = receiptNumber
            this.amountCents = amountCents
            this.currency = currency
            this.cardType = cardType
            this.cardTypeName = cardTypeName
            this.panLast4 = panLast4
            this.expiryDate = expiryDate
            this.aid = aid
            this.paymentType = paymentType
            this.resultCode = resultCode
            this.receiptTextMerchant = receiptTextMerchant
            this.receiptTextCustomer = receiptTextCustomer
            this.transactionTime = transactionTime
            this.createdAt = currentTimeStamp()
        }
    }

    override fun linkToPayment(
        cardTransactionId: Long,
        paymentId: Long,
    ) {
        val transaction =
            CardTransaction.findById(cardTransactionId)
                ?: throw NoSuchElementException("CardTransaction not found: $cardTransactionId")
        val payment =
            Payment.findById(paymentId)
                ?: throw NoSuchElementException("Payment not found: $paymentId")
        transaction.payment = payment
    }
}
