/**
 * UI state classes for the Audit Log feature.
 *
 * Defines the tab selection, filter states, and overall UI state
 * for the paginated audit log screen.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.auditlog.presentation.state

import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.core.presentation.error.UiError

/**
 * Tab selection for the audit log screen.
 */
enum class AuditLogTab {
    ENTRY_LOG,
    TRANSACTION_AUDIT,
}

/**
 * Filter state for entry log queries.
 *
 * @property type Log type filter (PRODUCT, CATEGORY, etc.)
 * @property operation Operation filter (CREATE, UPDATE, DELETE)
 * @property entityName Entity name partial match filter
 * @property entityId Exact entity ID filter
 * @property employeeNumber Employee number filter
 * @property fromDate Start date filter (ISO format)
 * @property toDate End date filter (ISO format)
 * @property search Global case-insensitive search across entityName, entityId, message
 */
data class EntryLogFilterState(
    val type: String? = null,
    val operation: String? = null,
    val entityName: String? = null,
    val entityId: String? = null,
    val employeeNumber: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    val search: String? = null,
)

/**
 * Filter state for transaction audit log queries.
 *
 * @property employeeId Employee ID filter
 * @property employeeNumber Employee number filter
 * @property operation Operation type filter
 * @property entityType Entity type filter
 * @property entityId Entity ID filter
 * @property entityName Entity name partial match filter
 * @property fromDate Start date filter (ISO format)
 * @property toDate End date filter (ISO format)
 * @property search Global case-insensitive search
 * @property amountMin Inclusive lower bound on amount impact
 * @property amountMax Inclusive upper bound on amount impact
 * @property authorizationType Exact authorization-type filter
 */
data class TransactionAuditFilterState(
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
)

/**
 * Overall UI state for the audit log screen.
 *
 * @property selectedTab Currently selected tab
 * @property entryLogs Current page of entry log items
 * @property entryLogPagination Pagination info for entry logs
 * @property entryLogFilter Active filters for entry logs
 * @property isEntryLogLoading Whether entry logs are loading
 * @property transactionAuditLogs Current page of transaction audit items
 * @property transactionAuditPagination Pagination info for transaction audit
 * @property transactionAuditFilter Active filters for transaction audit
 * @property isTransactionAuditLoading Whether transaction audit logs are loading
 * @property error Current error state
 * @property isFilterPanelVisible Whether filter panel is expanded
 */
data class AuditLogUiState(
    val selectedTab: AuditLogTab = AuditLogTab.ENTRY_LOG,
    val entryLogs: List<EntryLogDto.EntryLogResponseDto> = emptyList(),
    val entryLogPagination: ApiResponseDto.PaginationInfo? = null,
    val entryLogFilter: EntryLogFilterState = EntryLogFilterState(),
    val entryLogPageSize: Int = 20,
    val isEntryLogLoading: Boolean = false,
    val transactionAuditLogs: List<TransactionAuditDto.TransactionAuditLogDto> = emptyList(),
    val transactionAuditPagination: ApiResponseDto.PaginationInfo? = null,
    val transactionAuditFilter: TransactionAuditFilterState = TransactionAuditFilterState(),
    val transactionAuditPageSize: Int = 20,
    val isTransactionAuditLoading: Boolean = false,
    val error: UiError? = null,
    val isFilterPanelVisible: Boolean = false,
)
