package org.example.project.features.cardTerminal.domain.models

import org.example.project.features.cardTerminal.database.tables.CardTransactionTable
import org.example.project.features.payment.domain.models.Payment
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

class CardTransaction(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<CardTransaction>(CardTransactionTable)

    var payment by Payment optionalReferencedOn CardTransactionTable.paymentId
    var cardTerminal by CardTerminal referencedOn CardTransactionTable.cardTerminalId
    var traceNumber by CardTransactionTable.traceNumber
    var receiptNumber by CardTransactionTable.receiptNumber
    var amountCents by CardTransactionTable.amountCents
    var currency by CardTransactionTable.currency
    var cardType by CardTransactionTable.cardType
    var cardTypeName by CardTransactionTable.cardTypeName
    var panLast4 by CardTransactionTable.panLast4
    var expiryDate by CardTransactionTable.expiryDate
    var aid by CardTransactionTable.aid
    var paymentType by CardTransactionTable.paymentType
    var resultCode by CardTransactionTable.resultCode
    var receiptTextMerchant by CardTransactionTable.receiptTextMerchant
    var receiptTextCustomer by CardTransactionTable.receiptTextCustomer
    var transactionTime by CardTransactionTable.transactionTime
    var createdAt by CardTransactionTable.createdAt
}
