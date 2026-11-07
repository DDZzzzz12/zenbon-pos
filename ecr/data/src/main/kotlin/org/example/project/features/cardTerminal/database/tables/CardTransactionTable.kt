package org.example.project.features.cardTerminal.database.tables

import org.example.project.features.payment.database.tables.PaymentTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object CardTransactionTable : LongIdTable("card_transaction") {
    val paymentId = reference("payment_id", PaymentTable).nullable()
    val cardTerminalId = reference("card_terminal_id", CardTerminalTable)
    val traceNumber = varchar("trace_number", 50)
    val receiptNumber = varchar("receipt_number", 50).nullable()
    val amountCents = long("amount_cents")
    val currency = varchar("currency", 3).default("EUR")
    val cardType = integer("card_type").nullable()
    val cardTypeName = varchar("card_type_name", 100).nullable()
    val panLast4 = varchar("pan_last4", 4).nullable()
    val expiryDate = varchar("expiry_date", 4).nullable()
    val aid = varchar("aid", 50).nullable()
    val paymentType = varchar("payment_type", 20)
    val resultCode = integer("result_code").default(0)
    val receiptTextMerchant = text("receipt_text_merchant").nullable()
    val receiptTextCustomer = text("receipt_text_customer").nullable()
    val transactionTime = datetime("transaction_time")
    val createdAt = datetime("created_at")

    init {
        // Query hot paths — kept in sync with Flyway V86 (matching index names so fresh
        // installs (Exposed-created schema) and existing installs (migration) converge).
        index("idx_card_tx_payment_id", false, paymentId)
        index("idx_card_tx_terminal_time", false, cardTerminalId, transactionTime)
    }
}
