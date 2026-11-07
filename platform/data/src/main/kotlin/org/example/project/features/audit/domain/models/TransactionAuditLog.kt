/**
 * Transaction audit log entity model.
 *
 * Represents an immutable audit record for financial and operational transactions
 * with complete tracking of who, what, when, and how.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.audit.domain.models

import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/**
 * Transaction audit log entity.
 *
 * Immutable record of a transaction with complete audit information including:
 * - Employee and authorization details
 * - Operation and entity information
 * - Before/after state snapshots
 * - Context (device, IP, reason)
 * - Financial impact
 * - Fiscal compliance data
 */
class TransactionAuditLog(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<TransactionAuditLog>(TransactionAuditLogTable)

    /**
     * Unique transaction identifier.
     */
    var transactionId by TransactionAuditLogTable.transactionId

    // ========== Actor Information ==========

    /**
     * ID of employee who performed the action.
     */
    var employeeId by TransactionAuditLogTable.employeeId

    /**
     * Employee number for identification.
     */
    var employeeNumber by TransactionAuditLogTable.employeeNumber

    // ========== Authorization Information ==========

    /**
     * How the action was authorized (DIRECT, TEMPORARY_SESSION, APPROVAL_REQUEST).
     */
    var authorizationType by TransactionAuditLogTable.authorizationType

    /**
     * ID of manager who authorized (if applicable).
     */
    var authorizingManagerId by TransactionAuditLogTable.authorizingManagerId

    /**
     * Name of authorizing manager (if applicable).
     */
    var authorizingManagerName by TransactionAuditLogTable.authorizingManagerName

    // ========== Operation Information ==========

    /**
     * Type of operation performed.
     */
    var operation by TransactionAuditLogTable.operation

    /**
     * Type of entity affected.
     */
    var entityType by TransactionAuditLogTable.entityType

    /**
     * ID of the affected entity.
     */
    var entityId by TransactionAuditLogTable.entityId

    /**
     * Name or description of the affected entity.
     */
    var entityName by TransactionAuditLogTable.entityName

    // ========== State Tracking ==========

    /**
     * JSON snapshot of entity state before operation.
     */
    var beforeState by TransactionAuditLogTable.beforeState

    /**
     * JSON snapshot of entity state after operation.
     */
    var afterState by TransactionAuditLogTable.afterState

    /**
     * Human-readable summary of changes.
     */
    var changesSummary by TransactionAuditLogTable.changesSummary

    // ========== Context Information ==========

    /**
     * Device identifier where operation was performed.
     */
    var deviceId by TransactionAuditLogTable.deviceId

    /**
     * IP address of the client.
     */
    var ipAddress by TransactionAuditLogTable.ipAddress

    /**
     * Reason provided for the operation.
     */
    var reason by TransactionAuditLogTable.reason

    // ========== Financial Impact ==========

    /**
     * Financial impact of the operation.
     */
    var amountImpact by TransactionAuditLogTable.amountImpact

    /**
     * Currency code for financial impact.
     */
    var currencyCode by TransactionAuditLogTable.currencyCode

    // ========== Metadata ==========

    /**
     * Additional context as JSON.
     */
    var metadata by TransactionAuditLogTable.metadata

    /**
     * Timestamp of the operation.
     */
    var timestamp by TransactionAuditLogTable.timestamp

    /**
     * Reference to TSE/fiscal record.
     */
    var fiscalRecordId by TransactionAuditLogTable.fiscalRecordId

    /**
     * The request's `X-Request-ID`, or null for work with no request behind it.
     */
    var traceId by TransactionAuditLogTable.traceId

    /**
     * System creation timestamp.
     */
    var createdAt by TransactionAuditLogTable.createdAt
}
