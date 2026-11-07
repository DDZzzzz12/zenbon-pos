/**
 * Database table definition for transaction audit logging.
 *
 * Stores comprehensive audit trail for all financial and operational transactions
 * with dual-user tracking, state snapshots, and fiscal compliance support.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.audit.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Transaction audit log table.
 *
 * Stores immutable audit records for all significant operations in the POS system.
 * Each record captures who performed the action, how they were authorized,
 * what changed, and the complete before/after state for forensic analysis.
 *
 * Key features:
 * - Dual-user tracking (employee + authorizing manager)
 * - JSON state snapshots for complete audit trail
 * - Device and IP tracking for security
 * - Financial impact tracking
 * - TSE/fiscal device integration ready
 * - Indexed for fast querying
 */
object TransactionAuditLogTable : LongIdTable("transaction_audit_log") {
    /**
     * Unique transaction identifier (UUID format).
     * Used for external references and deduplication.
     */
    val transactionId = varchar("transaction_id", 100).uniqueIndex()

    // ========== Actor Information ==========

    /**
     * ID of employee who performed the action.
     */
    val employeeId = varchar("employee_id", 100).index()

    /**
     * Employee number for identification.
     */
    val employeeNumber = varchar("employee_number", 50)

    // ========== Authorization Information ==========

    /**
     * How the action was authorized.
     * Values: DIRECT, TEMPORARY_SESSION, APPROVAL_REQUEST
     */
    val authorizationType = varchar("authorization_type", 50)

    /**
     * ID of manager who authorized the action (if applicable).
     * Null for DIRECT authorization.
     */
    val authorizingManagerId = varchar("authorizing_manager_id", 100).nullable().index()

    /**
     * Name of authorizing manager (if applicable).
     * Denormalized for reporting.
     */
    val authorizingManagerName = varchar("authorizing_manager_name", 255).nullable()

    // ========== Operation Information ==========

    /**
     * Type of operation performed.
     * See TransactionOperation enum for possible values.
     */
    val operation = varchar("operation", 100).index()

    /**
     * Type of entity affected by the operation.
     * Values: ORDER, ORDER_ITEM, BILL, PAYMENT, TABLE, PRODUCT, etc.
     */
    val entityType = varchar("entity_type", 50).index()

    /**
     * ID of the affected entity.
     */
    val entityId = varchar("entity_id", 50).index()

    /**
     * Name or description of the affected entity.
     * e.g., "Pizza Margherita", "Table 5", "Order #123"
     */
    val entityName = varchar("entity_name", 255).nullable()

    // ========== State Tracking ==========

    /**
     * JSON snapshot of entity state before the operation.
     * Enables complete audit trail and rollback analysis.
     */
    val beforeState = text("before_state").nullable()

    /**
     * JSON snapshot of entity state after the operation.
     * Captures the result of the change.
     */
    val afterState = text("after_state").nullable()

    /**
     * Human-readable summary of changes made.
     * e.g., "Cancelled 2 items, total refund: €15.50"
     */
    val changesSummary = text("changes_summary").nullable()

    // ========== Context Information ==========

    /**
     * Device identifier where operation was performed.
     * Links to the specific POS terminal or client.
     */
    val deviceId = varchar("device_id", 100).nullable().index()

    /**
     * IP address of the client that made the request.
     */
    val ipAddress = varchar("ip_address", 50).nullable()

    /**
     * Reason provided for the operation.
     * Particularly important for cancellations and deletions.
     */
    val reason = text("reason").nullable()

    // ========== Financial Impact ==========

    /**
     * Financial impact of the operation.
     * Positive for additions, negative for refunds/cancellations.
     * e.g., -50.00 for a €50 refund
     */
    val amountImpact = decimal("amount_impact", 10, 2).nullable()

    /**
     * Currency code for the financial impact.
     */
    val currencyCode = varchar("currency_code", 3).default("EUR")

    // ========== Metadata ==========

    /**
     * Additional context as JSON.
     * Flexible storage for operation-specific details.
     */
    val metadata = text("metadata").nullable()

    /**
     * Timestamp of the operation (ISO 8601 format).
     * Stored as string for consistency with other tables.
     */
    val timestamp = varchar("timestamp", 50).index()

    /**
     * Reference to TSE (Technical Security Equipment) record.
     * Required for German fiscal compliance (Kassensicherungsverordnung).
     */
    val fiscalRecordId = varchar("fiscal_record_id", 100).nullable()

    /**
     * The request's `X-Request-ID`, as `TraceIdConfig` publishes it to the SLF4J MDC.
     *
     * Null for work with no request behind it — schedulers, startup. (NOT the TSE signing queue:
     * its `retryEntry` and `cancelEntry` entry points are request-backed and DO carry a trace id;
     * only the `processBatch` sweeper is background. See the correction in the design doc.) It is
     * what lets one failure be matched across the operator's error dialog, the server log line and
     * this row; before it, those three records of the same event shared no key.
     */
    val traceId = varchar("trace_id", 64).nullable().index("idx_transaction_audit_log_trace_id")

    /**
     * System creation timestamp.
     * Auto-set by database for record-keeping.
     */
    val createdAt = varchar("created_at", 50)
}
