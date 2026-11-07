/**
 * Ktor implementation of Audit Log API client.
 *
 * Stage C Batch 8: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import org.example.project.core.data.remote.AuditLogApiClient
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorAuditLogApiClient(
    private val httpCall: HttpCall,
) : AuditLogApiClient {
    companion object {
        private const val ENTRY_LOG_ENDPOINT = "api/entry-logs"
        private const val TRANSACTION_AUDIT_ENDPOINT = "api/audit/transactions"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun getPaginatedEntryLogs(
        type: String?,
        operation: String?,
        entityName: String?,
        entityId: String?,
        employeeNumber: String?,
        fromDate: String?,
        toDate: String?,
        search: String?,
        page: Int,
        size: Int,
        cursor: String?,
    ): EntryLogDto.PaginatedEntryLogResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$ENTRY_LOG_ENDPOINT/paginated")) {
                parameter("page", page)
                parameter("size", size)
                type?.let { parameter("type", it) }
                operation?.let { parameter("operation", it) }
                entityName?.let { parameter("entityName", it) }
                entityId?.let { parameter("entityId", it) }
                employeeNumber?.let { parameter("employeeNumber", it) }
                fromDate?.let { parameter("fromDate", it) }
                toDate?.let { parameter("toDate", it) }
                search?.let { parameter("search", it) }
                // Keyset pagination: server ignores `page` when cursor is present.
                cursor?.let { parameter("cursor", it) }
            }
        }

    override suspend fun getPaginatedTransactionAuditLogs(
        employeeId: String?,
        employeeNumber: String?,
        operation: String?,
        entityType: String?,
        entityId: String?,
        entityName: String?,
        fromDate: String?,
        toDate: String?,
        search: String?,
        amountMin: String?,
        amountMax: String?,
        authorizationType: String?,
        page: Int,
        size: Int,
    ): TransactionAuditDto.PaginatedTransactionAuditResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$TRANSACTION_AUDIT_ENDPOINT/paginated")) {
                parameter("page", page)
                parameter("size", size)
                employeeId?.let { parameter("employeeId", it) }
                employeeNumber?.let { parameter("employeeNumber", it) }
                operation?.let { parameter("operation", it) }
                entityType?.let { parameter("entityType", it) }
                entityId?.let { parameter("entityId", it) }
                entityName?.let { parameter("entityName", it) }
                fromDate?.let { parameter("fromDate", it) }
                toDate?.let { parameter("toDate", it) }
                search?.let { parameter("search", it) }
                amountMin?.let { parameter("amountMin", it) }
                amountMax?.let { parameter("amountMax", it) }
                authorizationType?.let { parameter("authorizationType", it) }
            }
        }
}
