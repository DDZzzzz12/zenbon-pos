/**
 * Service interface for transaction audit logging business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 9.
 */
package org.example.project.features.audit.domain.service

import org.example.project.core.domain.dto.audit.TransactionAuditDto

/**
 * Transaction audit service interface.
 *
 * Provides business logic for transaction logging including:
 * - Automatic transaction ID generation
 * - JSON serialization of state objects
 * - IP address extraction from request context
 * - Audit log querying and retrieval
 */
interface TransactionAuditService {
    /**
     * Log a transaction with full audit details.
     *
     * Automatically generates transaction ID and serializes before/after states to JSON.
     *
     * @param traceId The request's `X-Request-ID`, tying this row to the server log line and to the
     *   id the operator was shown. Null for genuinely background work — the TSE sweeper, startup.
     *   **Capture it at the top of the calling operation, not here**: MDC is thread-local and this
     *   method is normally reached from inside a `dbQuery`, i.e. past a suspension point.
     * @return generated transaction ID
     */
    suspend fun logTransaction(
        employeeId: String,
        employeeName: String? = null,
        employeeNumber: String,
        authorizationType: String,
        authorizingManagerId: String,
        operation: String,
        entityType: String,
        entityId: String,
        entityName: String? = null,
        beforeState: Any? = null,
        afterState: Any? = null,
        deviceId: String? = null,
        ipAddress: String? = null,
        reason: String? = null,
        amountImpact: Double? = null,
        fiscalRecordId: String? = null,
        metadata: Map<String, Any>? = null,
        traceId: String? = null,
    ): String

    /**
     * Query transaction audit logs with filters.
     */
    suspend fun getTransactionLogs(query: TransactionAuditDto.TransactionAuditQueryDto): List<TransactionAuditDto.TransactionAuditLogDto>

    /**
     * Get specific transaction audit log by transaction ID.
     */
    suspend fun getTransactionById(transactionId: String): TransactionAuditDto.TransactionAuditLogDto

    /**
     * Get all transaction logs for a specific employee.
     */
    suspend fun getEmployeeTransactions(
        employeeId: String,
        limit: Int = 100,
    ): List<TransactionAuditDto.TransactionAuditLogDto>

    /**
     * Get all transaction logs for a specific entity.
     */
    suspend fun getEntityTransactions(
        entityType: String,
        entityId: String,
    ): List<TransactionAuditDto.TransactionAuditLogDto>

    /**
     * Get paginated transaction audit logs with optional filters.
     */
    suspend fun getPaginatedTransactionLogs(
        query: TransactionAuditDto.PaginatedTransactionAuditQueryDto,
    ): TransactionAuditDto.PaginatedTransactionAuditResponseDto
}
