/**
 * Exposed DAO entity for TseTransaction.
 *
 * Provides object-relational mapping for TSE transactions with proper
 * relationship management and type-safe database operations. Supports
 * German fiscal compliance requirements.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.domain.models

import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * TSE Transaction entity for German fiscal compliance.
 *
 * Manages TSE (Technical Security Element) transactions for German fiscal compliance.
 * Stores digital signatures and transaction data required for tax authorities.
 */
class TseTransaction(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<TseTransaction>(TseTransactionTable)

    /** Serial number of the TSE device */
    var tseSerialNumber by TseTransactionTable.tseSerialNumber

    /**
     * See [TseTransactionTable.tseSerialHex] — the canonical hex serial; V82. Read it through
     * `TseTransaction.serialHex()` rather than directly, so pre-V82 rows still resolve.
     */
    var tseSerialHex by TseTransactionTable.tseSerialHex

    /** Sequential transaction number */
    var transactionNumber by TseTransactionTable.transactionNumber

    /** Process type (e.g., "Bestellung-V1", "Kassenbeleg-V1") */
    var processType by TseTransactionTable.processType

    /** Process data signed by TSE (used for QR code generation) */
    var processDataHash by TseTransactionTable.processDataHash

    /** m3: TSE_ZEITFORMAT — the device's internal log-time format (Anhang E p. 28). */
    var logTimeFormat by TseTransactionTable.logTimeFormat

    /** Digital signature from TSE */
    var signature by TseTransactionTable.signature

    /** Transaction start timestamp */
    var startTime by TseTransactionTable.startTime

    /** Transaction end timestamp */
    var endTime by TseTransactionTable.endTime

    /** Cash register or workstation ID */
    var clientId by TseTransactionTable.clientId

    /** TSE mode used for this transaction (SIMULATION, NORMAL, DISABLED) */
    var tseMode by TseTransactionTable.tseMode

    /** See [TseTransactionTable.referenceId] — the Vorgang this signature belongs to. */
    var referenceId by TseTransactionTable.referenceId

    /** See [TseTransactionTable.entityType]. */
    var entityType by TseTransactionTable.entityType

    val order by Orders optionalReferrersOn OrdersTable.tseTransactionId

    val bill by Bill optionalReferrersOn BillTable.tseTransactionId

    val cancellation by Cancellation optionalReferrersOn CancellationTable.tseTransactionId

    /** Reference to exported file (e.g., DSFinV-K export) */
    var exportedFileReference by TseTransactionTable.exportedFileReference

    /** TSE log timestamp (epoch seconds from TSE hardware; normalized at the persistence
     * seam — see RealTseTransactionServiceImpl.toEpochSeconds) */
    var tseTimestamp by TseTransactionTable.tseTimestamp

    /** TSE StartTransaction log timestamp (epoch seconds from TSE hardware) */
    var tseStartTimestamp by TseTransactionTable.tseStartTimestamp

    /** Sequential signature counter from TSE */
    var signatureCounter by TseTransactionTable.signatureCounter

    /** Signature algorithm used by TSE (e.g., "ecdsa-plain-SHA384") */
    var signatureAlgorithm by TseTransactionTable.signatureAlgorithm

    /** TSE public key (Base64 encoded) */
    var publicKey by TseTransactionTable.publicKey

    /** TSE certificate (Base64 encoded) */
    var certificate by TseTransactionTable.certificate

    /** See [org.example.project.features.tse.domain.models.TseTransactionState]; V82. */
    var state by TseTransactionTable.state

    /** The replacing row's id when this one is SUPERSEDED; V82. */
    var supersededById by TseTransactionTable.supersededById
}
