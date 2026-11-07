package org.example.project.features.tse.database.tables

import org.example.project.core.domain.enums.tse.TseEntityType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * One row per Vorgang that went to the TSE signing queue instead of being signed immediately: why,
 * since when, and when it was signed late or its Nachsignierung abandoned.
 *
 * Never deleted by queue cleanup — it is the Vorgang's permanent Nachsignierung record, so a Vorgang
 * signed late stays distinguishable from one signed on time. Flyway: V82__schema_cash_and_fiscal.sql.
 */
object TseLateSigningTable : LongIdTable("tse_late_signing", "id") {
    val entityType = enumerationByName<TseEntityType>("entity_type", 20)
    val referenceId = varchar("reference_id", 100)
    val queuedAt = datetime("queued_at")

    /** A `TseLateSigningReason` name. */
    val reasonCode = varchar("reason_code", 50)

    /** Operator-readable cause, taken from the failure at queue time. */
    val reasonText = varchar("reason_text", 500)
    val signedAt = datetime("signed_at").nullable()
    val tseTransactionId = reference("tse_transaction_id", TseTransactionTable).nullable()
    val abandonedAt = datetime("abandoned_at").nullable()

    init {
        uniqueIndex("uq_tse_late_signing_ref", entityType, referenceId)
    }
}
