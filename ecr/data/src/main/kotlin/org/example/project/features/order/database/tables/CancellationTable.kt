/**
 * Exposed table definition for cancellations.
 *
 * Defines the database schema structure for order item cancellations with proper
 * column types, constraints, and relationships. Supports tracking cancelled items
 * with reasons and TSE transaction references for audit compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.order.database.tables

import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for cancellation table.
 *
 * Manages cancellation records for order items including reason tracking,
 * employee information, and TSE transaction references for compliance.
 * Essential for audit trails and financial reporting.
 */
object CancellationTable : LongIdTable("cancellation", "id") {
    /** Reference to cancelled order item */
    val orderItemId = reference("order_item_id", OrderItemTable)

    /** Reason for cancellation */
    val reason = varchar("reason", 500).nullable()

    /** Employee who performed the cancellation */
    val employeeId = reference("user_mapping_id", EmployeeTable)

    /** When the cancellation was created */
    val createdAt = datetime("created_at")

    val zBonId = reference("zbon_id", ZBonTable).nullable()

    /** Reference to TSE transaction if signed */
    val tseTransactionId = reference("tse_transaction_id", TseTransactionTable).nullable()

    /** TSE signing status for tracking queue-based signing (Nachsignieren) */
    val tseSigningStatus =
        enumerationByName<TseSigningStatus>("tse_signing_status", 20)
            .default(TseSigningStatus.PENDING)

    /** Whether this cancellation was created during training mode */
    val isTraining = bool("is_training").default(false)

    /**
     * Groups the rows created by ONE cancel action into one DSFinV-K Vorgang (M2).
     *
     * `Cancellation` is per-item, but cancelling N items in one action is ONE Vorgang — exactly as
     * an order of N items is one `Orders` entity, one `AVBestellung` Vorgang and one TSE signature
     * covering all its lines. Without this key the export split one action into N Vorgänge while
     * the signature covered all N, so no individual Beleg could be verified against it.
     *
     * Nullable for rows written before this column existed; the export falls back to grouping
     * those by their shared `tseTransactionId`, which served as the de-facto batch key.
     */
    val batchId = varchar("batch_id", 36).nullable()

    init {
        // Cancellation reporting by date range — kept in sync with Flyway V84.
        index("idx_cancellation_created_at", false, createdAt)
        index("idx_cancellation_batch_id", false, batchId)
    }
}
