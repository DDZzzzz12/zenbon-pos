/**
 * Data Transfer Objects for transaction audit logging.
 *
 * Provides comprehensive audit trail structures for all financial and operational
 * transactions with dual-user tracking and state snapshots.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.audit

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.ApiResponseDto

/**
 * Container object for all transaction audit-related DTOs.
 *
 * Organizes DTOs for transaction logging, querying, and audit trail management
 * to maintain clear separation of concerns and consistent API structure.
 */
@Suppress("UnsafeOptInUsageError")
object TransactionAuditDto {
    /**
     * Transaction audit log response DTO.
     *
     * Contains complete audit information including employee, authorization,
     * operation details, and state snapshots.
     *
     * @property id Unique audit log ID
     * @property transactionId Unique transaction identifier
     * @property employeeId ID of employee who performed the action
     * @property employeeNumber Employee number for identification
     * @property authorizationType How the action was authorized (DIRECT, TEMPORARY_SESSION, APPROVAL_REQUEST)
     * @property authorizingManagerId ID of manager who authorized (if applicable)
     * @property authorizingManagerName Name of authorizing manager (if applicable)
     * @property operation Type of operation performed
     * @property entityType Type of entity affected (ORDER, ORDER_ITEM, BILL, etc.)
     * @property entityId ID of affected entity
     * @property entityName Name/description of affected entity
     * @property beforeState JSON snapshot of entity state before operation
     * @property afterState JSON snapshot of entity state after operation
     * @property changesSummary Human-readable summary of changes
     * @property deviceId Device where operation was performed
     * @property ipAddress IP address of client
     * @property reason Reason for operation (for cancellations, deletions)
     * @property amountImpact Financial impact (negative for refunds/cancellations)
     * @property currencyCode Currency of financial impact
     * @property metadata Additional context as JSON
     * @property timestamp ISO timestamp of operation
     * @property fiscalRecordId TSE/fiscal device record reference
     * @property traceId The request's `X-Request-ID`, tying this row to the server log line and to
     *   the id the operator was shown in the error dialog. Null for work with no request behind it.
     */
    @Serializable
    data class TransactionAuditLogDto(
        val id: Long,
        val transactionId: String,
        val employeeId: String,
        val employeeNumber: String,
        val authorizationType: String,
        val authorizingManagerId: String? = null,
        val authorizingManagerName: String? = null,
        val operation: String,
        val entityType: String,
        val entityId: String,
        val entityName: String? = null,
        val beforeState: String? = null,
        val afterState: String? = null,
        val changesSummary: String? = null,
        val deviceId: String? = null,
        val ipAddress: String? = null,
        val reason: String? = null,
        val amountImpact: String? = null,
        val currencyCode: String = "EUR",
        val metadata: String? = null,
        val timestamp: String,
        val fiscalRecordId: String? = null,
        val traceId: String? = null,
    )

    /**
     * Query parameters for filtering transaction audit logs.
     *
     * @property employeeId Filter by employee who performed action
     * @property authorizingManagerId Filter by authorizing manager
     * @property operation Filter by operation type
     * @property entityType Filter by entity type
     * @property entityId Filter by specific entity
     * @property fromDate Filter by timestamp (ISO format) - inclusive
     * @property toDate Filter by timestamp (ISO format) - inclusive
     * @property limit Maximum number of results (default 100)
     */
    @Serializable
    data class TransactionAuditQueryDto(
        val employeeId: String? = null,
        val authorizingManagerId: String? = null,
        val operation: String? = null,
        val entityType: String? = null,
        val entityId: String? = null,
        val fromDate: String? = null,
        val toDate: String? = null,
        val limit: Int = 100,
    )

    /**
     * Query parameters for paginated transaction audit log retrieval.
     *
     * @property employeeId Filter by employee who performed action
     * @property employeeNumber Filter by employee number
     * @property operation Filter by operation type
     * @property entityType Filter by entity type
     * @property entityId Filter by specific entity ID
     * @property entityName Filter by entity name (partial match)
     * @property fromDate Filter from date (ISO format) - inclusive
     * @property toDate Filter to date (ISO format) - inclusive
     * @property search Global case-insensitive search (OR across entityName, entityId, transactionId, reason, changesSummary, employeeNumber)
     * @property amountMin Inclusive lower bound on amountImpact
     * @property amountMax Inclusive upper bound on amountImpact
     * @property authorizationType Exact match on AuthorizationType name
     * @property page Page number (0-indexed)
     * @property pageSize Number of items per page
     */
    @Serializable
    data class PaginatedTransactionAuditQueryDto(
        val employeeId: String? = null,
        val employeeNumber: String? = null,
        val operation: String? = null,
        val entityType: String? = null,
        val entityId: String? = null,
        val entityName: String? = null,
        val fromDate: String? = null,
        val toDate: String? = null,
        val search: String? = null,
        val amountMin: String? = null,
        val amountMax: String? = null,
        val authorizationType: String? = null,
        val page: Int = 0,
        val pageSize: Int = 20,
    )

    /**
     * Paginated response for transaction audit log queries.
     *
     * @property logs List of transaction audit log DTOs for the current page
     * @property pagination Pagination metadata (currentPage, totalPages, etc.)
     */
    @Serializable
    data class PaginatedTransactionAuditResponseDto(
        val logs: List<TransactionAuditLogDto>,
        val pagination: ApiResponseDto.PaginationInfo,
    )

    /**
     * Transaction operations that can be logged.
     *
     * Comprehensive list of all auditable operations across the POS system.
     */
    @Serializable
    enum class TransactionOperation {
        // Order operations
        CREATE_ORDER,
        UPDATE_ORDER,
        CANCEL_ORDER,
        DELETE_ORDER,
        FINALIZE_ORDER,
        REOPEN_ORDER,

        // Order item operations
        ADD_ORDER_ITEM,
        UPDATE_ORDER_ITEM,
        CANCEL_ORDER_ITEM,
        DELETE_ORDER_ITEM,
        TRANSFER_ORDER_ITEM,

        // Bill operations
        CREATE_BILL,
        SPLIT_BILL,
        MERGE_BILL,
        VOID_BILL,
        CLOSE_BILL,

        // Payment operations
        PROCESS_PAYMENT,
        REFUND_PAYMENT,
        VOID_PAYMENT,
        PARTIAL_PAYMENT,

        // Discount/Price operations
        APPLY_DISCOUNT,
        REMOVE_DISCOUNT,
        OVERRIDE_PRICE,
        APPLY_VOUCHER,

        // Table operations
        TRANSFER_TABLE,
        MERGE_TABLE,
        SPLIT_TABLE,

        // Cash register operations
        OPEN_CASH_REGISTER,
        CLOSE_CASH_REGISTER,
        CASH_IN,
        CASH_OUT,

        // Product operations
        CREATE_PRODUCT,
        UPDATE_PRODUCT,
        DELETE_PRODUCT,
        UPDATE_STOCK,

        // Employee operations
        CREATE_EMPLOYEE,
        UPDATE_EMPLOYEE,
        DELETE_EMPLOYEE,
        CHANGE_PERMISSIONS,

        // Z-Bon operations
        CREATE_ZBON,

        // Kassenbuch operations
        CREATE_KASSENBUCH_ENTRY,

        // Business partner operations
        MERGE_BUSINESS_PARTNER,

        // Payment allocation (Zuordnung) operations
        /**
         * The nightly *Stornolauf* removed an allocation because the bank returned the entry it paid
         * from (Rücklastschrift). Written by the system, never by an operator.
         */
        VOID_RETURNED_DEBIT_ALLOCATION,

        // Time tracking operations
        CLOCK_IN,
        CLOCK_OUT,
        START_BREAK,
        END_BREAK,
        CREATE_MANUAL_TIME_ENTRY,
        DELETE_TIME_ENTRY,
    }

    /**
     * Authorization types for transaction logging.
     *
     * Indicates how the employee was authorized to perform the operation.
     */
    @Serializable
    enum class AuthorizationType {
        /**
         * Employee has direct permission for this operation.
         */
        DIRECT,

        /**
         * Authorized via temporary manager session on the device.
         */
        TEMPORARY_SESSION,

        /**
         * Authorized via real-time manager approval request.
         */
        APPROVAL_REQUEST,
    }

    /**
     * Entity types that can be audited.
     *
     * Categorizes different entities affected by operations.
     */
    @Serializable
    enum class EntityType {
        ORDER,
        ORDER_ITEM,
        BILL,
        PAYMENT,
        TABLE,
        PRODUCT,
        EMPLOYEE,
        CASH_REGISTER,
        DISCOUNT,
        VOUCHER,
        ZBON,
        KASSENBUCH,
        CANCELLATION,
        TIME_ENTRY,
    }
}
