package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.ApiResponseDto

/**
 * Data Transfer Objects for EntryLog API operations.
 *
 * Organizes DTOs by functionality: responses and queries
 * to maintain clear separation of concerns and consistent API structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all EntryLog-related DTOs.
 *
 * Organizes DTOs by functionality to maintain clear separation
 * of concerns and consistent API structure for operation logging.
 */
object EntryLogDto {
    /**
     * Response DTO for EntryLog data.
     *
     * Used when returning operation log data to clients for
     * synchronization and audit purposes.
     */
    @Serializable
    data class EntryLogResponseDto(
        /** Unique identifier for the log entry */
        val id: Long,
        /** Type of entity that was affected (PRODUCT, CATEGORY, etc.) */
        val type: String,
        /** Operation that was performed (CREATE, UPDATE, DELETE) */
        val operation: String,
        /** ID of the affected entity */
        val entityId: String,
        /** Name or description of the affected entity */
        val entityName: String,
        /** Detailed description of what was done (nullable for older entries) */
        val message: String? = null,
        /** ID of the user who performed the operation (0 for system) */
        val userId: String,
        /** Timestamp when the operation was performed (epoch milliseconds) */
        val createdAt: String,
    )

    /**
     * Query parameters for paginated entry log retrieval.
     *
     * @property type Filter by log type (PRODUCT, CATEGORY, etc.)
     * @property operation Filter by operation (CREATE, UPDATE, DELETE)
     * @property entityName Filter by entity name (partial match)
     * @property entityId Filter by exact entity ID
     * @property employeeNumber Filter by employee number
     * @property fromDate Filter from date (ISO format) - inclusive
     * @property toDate Filter to date (ISO format) - inclusive
     * @property search Global case-insensitive search across entityName, entityId, message
     * @property page Page number (0-indexed) — ignored when [cursor] is set
     * @property pageSize Number of items per page
     * @property cursor Opaque keyset cursor from a previous response's `nextCursor`.
     *   When set, the server ignores [page] and returns rows strictly after the
     *   cursor position in `(created_at DESC, id DESC)` order.
     */
    @Serializable
    data class EntryLogQueryDto(
        val type: String? = null,
        val operation: String? = null,
        val entityName: String? = null,
        val entityId: String? = null,
        val employeeNumber: String? = null,
        val fromDate: String? = null,
        val toDate: String? = null,
        val search: String? = null,
        val page: Int = 0,
        val pageSize: Int = 20,
        val cursor: String? = null,
    )

    /**
     * Paginated response for entry log queries.
     *
     * @property logs List of entry log DTOs for the current page
     * @property pagination Pagination metadata (currentPage, totalPages, etc.)
     * @property nextCursor Opaque keyset cursor for the next page, or null when this
     *   is the last page. Pass it back as the `cursor` query parameter to page
     *   without OFFSET scans. Nullable with default for backward compatibility.
     */
    @Serializable
    data class PaginatedEntryLogResponseDto(
        val logs: List<EntryLogResponseDto>,
        val pagination: ApiResponseDto.PaginationInfo,
        val nextCursor: String? = null,
    )
}
