package org.example.project.features.audit.domain.service

import org.example.project.core.domain.dto.setup.EntryLogDto

/**
 * Service interface for EntryLog operations.
 *
 * Provides business logic methods for querying operation logs.
 * Methods return DTOs directly and throw
 * [org.example.project.infrastructure.errors.PosException] on error.
 *
 * Note: the legacy [getLogsAfter] timestamp-based sync method was removed in Phase 9
 * of the change-feed redesign. The authoritative feed is GET /api/changes?since={seq}.
 */
interface EntryLogService {
    /**
     * Get all entry logs for a specific entity.
     *
     * Retrieves the complete operation history for a single entity,
     * useful for audit trails and debugging.
     *
     * @param entityId The ID of the entity
     * @return List of entry log DTOs for the entity
     */
    suspend fun getLogsByEntityId(entityId: String): List<EntryLogDto.EntryLogResponseDto>

    /**
     * Get paginated entry logs with optional filters.
     *
     * Retrieves a page of entry logs matching the provided query filters.
     *
     * @param query Query parameters including filters and pagination info
     * @return Paginated entry log response with pagination metadata
     */
    suspend fun getPaginatedLogs(query: EntryLogDto.EntryLogQueryDto): EntryLogDto.PaginatedEntryLogResponseDto
}
