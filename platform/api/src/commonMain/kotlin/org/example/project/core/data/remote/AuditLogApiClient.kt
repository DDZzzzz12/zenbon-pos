/**
 * API client interface for Audit Log operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.setup.EntryLogDto

/**
 * Audit Log API client for retrieving paginated audit logs.
 */
interface AuditLogApiClient {
    /**
     * Fetch a page of entry logs.
     *
     * @param cursor opaque keyset cursor from a previous response's `nextCursor`;
     *   when set the server ignores [page] and pages by `(created_at, id)` keyset.
     */
    suspend fun getPaginatedEntryLogs(
        type: String? = null,
        operation: String? = null,
        entityName: String? = null,
        entityId: String? = null,
        employeeNumber: String? = null,
        fromDate: String? = null,
        toDate: String? = null,
        search: String? = null,
        page: Int = 0,
        size: Int = 20,
        cursor: String? = null,
    ): EntryLogDto.PaginatedEntryLogResponseDto

    suspend fun getPaginatedTransactionAuditLogs(
        employeeId: String? = null,
        employeeNumber: String? = null,
        operation: String? = null,
        entityType: String? = null,
        entityId: String? = null,
        entityName: String? = null,
        fromDate: String? = null,
        toDate: String? = null,
        search: String? = null,
        amountMin: String? = null,
        amountMax: String? = null,
        authorizationType: String? = null,
        page: Int = 0,
        size: Int = 20,
    ): TransactionAuditDto.PaginatedTransactionAuditResponseDto
}
