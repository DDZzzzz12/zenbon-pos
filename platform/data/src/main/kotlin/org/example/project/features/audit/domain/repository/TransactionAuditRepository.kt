/**
 * Repository interface for transaction audit log data access.
 *
 * Defines data access operations for transaction audit logging.
 * Returns plain entities; services wrap calls in dbQuery with Result.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.audit.domain.repository

import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.features.audit.domain.models.TransactionAuditLog

/**
 * Transaction audit repository interface.
 *
 * Handles data persistence and retrieval for transaction audit logs.
 * Returns plain entities; exceptions propagate to service layer.
 */
interface TransactionAuditRepository {
    /**
     * Create a new transaction audit log entry.
     *
     * @param transactionId Unique transaction identifier
     * @param employeeId ID of employee performing action
     * @param employeeName Name of employee
     * @param employeeNumber Employee number
     * @param authorizationType How action was authorized
     * @param authorizingManagerId ID of authorizing manager (if applicable)
     * @param authorizingManagerName Name of authorizing manager (if applicable)
     * @param operation Operation type
     * @param entityType Entity type affected
     * @param entityId Entity ID
     * @param entityName Entity name/description
     * @param beforeState JSON before state
     * @param afterState JSON after state
     * @param changesSummary Summary of changes
     * @param deviceId Device identifier
     * @param ipAddress Client IP address
     * @param reason Reason for operation
     * @param amountImpact Financial impact
     * @param currencyCode Currency code
     * @param metadata Additional metadata as JSON
     * @param timestamp Operation timestamp
     * @param fiscalRecordId TSE/fiscal record reference
     * @param traceId The request's `X-Request-ID`, or null for work with no request behind it.
     *   Defaulted so a caller that has no trace to pass need not name it; a caller that DOES have
     *   one must pass it explicitly, because this layer cannot read the MDC safely — see
     *   `TseSigningQueueServiceImpl.cancelEntry`, which captures it before its own `dbQuery`.
     * @return Created TransactionAuditLog entity
     */
    fun create(
        transactionId: String,
        employeeId: String,
        employeeName: String? = null,
        employeeNumber: String,
        authorizationType: String,
        authorizingManagerId: String?,
        authorizingManagerName: String? = null,
        operation: String,
        entityType: String,
        entityId: String,
        entityName: String?,
        beforeState: String?,
        afterState: String?,
        changesSummary: String?,
        deviceId: String?,
        ipAddress: String?,
        reason: String?,
        amountImpact: Double?,
        currencyCode: String,
        metadata: String?,
        timestamp: String,
        fiscalRecordId: String?,
        traceId: String? = null,
    ): TransactionAuditLog

    /**
     * Find transaction audit log by transaction ID.
     *
     * @param transactionId Unique transaction identifier
     * @return TransactionAuditLog or null if not found
     */
    fun findByTransactionId(transactionId: String): TransactionAuditLog?

    /**
     * Query transaction audit logs with filters.
     *
     * @param query Query parameters for filtering
     * @return List of matching TransactionAuditLog entries
     */
    fun findByQuery(query: TransactionAuditDto.TransactionAuditQueryDto): List<TransactionAuditLog>

    /**
     * Get all transaction audit logs for a specific employee.
     *
     * @param employeeId Employee ID
     * @param limit Maximum number of results
     * @return List of TransactionAuditLog entries
     */
    fun findByEmployee(
        employeeId: String,
        limit: Int = 100,
    ): List<TransactionAuditLog>

    /**
     * Get all transaction audit logs for a specific entity.
     *
     * @param entityType Entity type
     * @param entityId Entity ID
     * @return List of TransactionAuditLog entries
     */
    fun findByEntity(
        entityType: String,
        entityId: String,
    ): List<TransactionAuditLog>

    /**
     * Find transaction audit logs with pagination and filtering.
     *
     * Retrieves a page of logs matching the provided filters,
     * along with the total count for pagination metadata.
     *
     * @param query Paginated query parameters including filters and pagination
     * @return Pair of (totalCount, logs)
     */
    fun findPaginated(query: TransactionAuditDto.PaginatedTransactionAuditQueryDto): Pair<Long, List<TransactionAuditLog>>
}
