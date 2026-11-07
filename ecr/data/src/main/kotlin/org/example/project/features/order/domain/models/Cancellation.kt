/**
 * Exposed DAO entity for Cancellation.
 *
 * Provides object-relational mapping for order item cancellations with proper
 * relationship management and type-safe database operations. Tracks cancelled
 * items with audit information and compliance requirements.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.domain.models

import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.zBon.domain.models.ZBon
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Cancellation entity for tracking order item cancellations.
 *
 * Manages cancellation records including the cancelled item, reason,
 * responsible employee, and associated TSE transaction for compliance.
 * Essential for audit trails and financial reporting.
 */
class Cancellation(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Cancellation>(CancellationTable)

    /** Reference to the cancelled order item */
    var orderItem by OrderItem referencedOn CancellationTable.orderItemId

    /** Reason for the cancellation */
    var reason by CancellationTable.reason

    /** Employee who performed the cancellation */
    var employeeId by Employee referencedOn CancellationTable.employeeId

    /** When the cancellation was created */
    var createdAt by CancellationTable.createdAt

    var zBon by ZBon optionalReferencedOn CancellationTable.zBonId

    /** Reference to TSE transaction if signed */
    var tseTransaction by TseTransaction optionalReferencedOn CancellationTable.tseTransactionId

    /** TSE signing status for tracking queue-based signing (Nachsignieren) */
    var tseSigningStatus by CancellationTable.tseSigningStatus

    /** Whether this cancellation was created during training mode */
    var isTraining by CancellationTable.isTraining

    /** See [CancellationTable.batchId] — groups one cancel action into one DSFinV-K Vorgang. */
    var batchId by CancellationTable.batchId
}
