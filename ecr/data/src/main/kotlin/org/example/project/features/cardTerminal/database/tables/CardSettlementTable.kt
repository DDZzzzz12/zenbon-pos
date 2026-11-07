package org.example.project.features.cardTerminal.database.tables

import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

object CardSettlementTable : LongIdTable("card_settlement") {
    val cardTerminalId = reference("card_terminal_id", CardTerminalTable)
    val zbonId = reference("zbon_id", ZBonTable).nullable()
    val settlementTime = datetime("settlement_time")
    val success = bool("success")
    val resultCode = integer("result_code").default(0)
    val receiptText = text("receipt_text").nullable()
    val totalAmountCents = long("total_amount_cents").default(0)
    val totalTransactionCount = integer("total_transaction_count").default(0)
    val createdAt = datetime("created_at")

    init {
        // Query hot paths — kept in sync with Flyway V86 (matching index names so fresh
        // installs (Exposed-created schema) and existing installs (migration) converge).
        index("idx_card_settlement_terminal", false, cardTerminalId)
        index("idx_card_settlement_zbon", false, zbonId)
    }
}
