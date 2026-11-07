package org.example.project.features.cardTerminal.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object CardSettlementDetailTable : LongIdTable("card_settlement_detail") {
    val cardSettlementId = reference("card_settlement_id", CardSettlementTable, onDelete = ReferenceOption.CASCADE)
    val cardType = integer("card_type")
    val cardTypeName = varchar("card_type_name", 100)
    val transactionCount = integer("transaction_count").default(0)
    val totalAmountCents = long("total_amount_cents").default(0)
}
