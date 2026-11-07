package org.example.project.features.audit.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.setup.EntryLogDto
import org.example.project.features.audit.domain.models.EntryLog

/**
 * Repository interface for EntryLog operations.
 *
 * Provides data access methods for querying operation logs.
 * Returns plain entities; services wrap calls in dbQuery.
 *
 * Note: the legacy [getLogsAfter] timestamp-based method was removed in Phase 9 of the
 * change-feed redesign. The authoritative feed is [getChangesSince] (by sequence id).
 */
interface EntryLogRepository {
    /**
     * Get all entry logs for a specific entity.
     *
     * Retrieves the complete operation history for a single entity,
     * useful for audit trails and debugging.
     *
     * @param entityId The ID of the entity
     * @return List of entry logs for the entity
     */
    fun getLogsByEntityId(entityId: String): List<EntryLog>

    /**
     * Find entry logs with pagination and filtering.
     *
     * Retrieves a page of entry logs matching the provided filters,
     * along with the total count for pagination metadata.
     *
     * Two paging modes:
     * - Offset mode (default): applies `LIMIT pageSize+1 OFFSET page*pageSize`.
     * - Keyset mode: when [cursorCreatedAt]/[cursorId] are set, the offset is
     *   ignored and a `(created_at, id) < (cursorCreatedAt, cursorId)` keyset
     *   predicate is applied instead, matching the `created_at DESC, id DESC`
     *   sort. Constant cost regardless of page depth.
     *
     * In both modes up to `pageSize + 1` rows are returned — the extra row is a
     * look-ahead so the caller can detect whether another page exists; the
     * caller must trim to `pageSize`. The returned count is the total number of
     * rows matching the filters (cursor predicate excluded), so it is stable
     * across pages.
     *
     * @param query Query parameters including filters and pagination
     * @param cursorCreatedAt createdAt of the last row of the previous page (keyset mode)
     * @param cursorId id of the last row of the previous page (keyset tiebreaker)
     * @return Pair of (totalCount, logs) — logs may contain pageSize+1 rows
     */
    fun findPaginated(
        query: EntryLogDto.EntryLogQueryDto,
        cursorCreatedAt: LocalDateTime? = null,
        cursorId: Long? = null,
    ): Pair<Long, List<EntryLog>>

    /** Client-synced change-feed rows with id > [sinceSeq], ascending by id, capped at [limit]. */
    fun getChangesSince(
        sinceSeq: Long,
        limit: Int,
    ): List<EntryLog>

    /** Oldest retained entry_log id, or null if empty. */
    fun minSeq(): Long?

    /** Newest entry_log id, or null if empty. */
    fun maxSeq(): Long?

    /** Newest entry_log id among client-synced, non-ERROR rows, or null if none. */
    fun maxClientSyncedSeq(): Long?

    /** Delete rows older than [cutoff]; returns rows removed. Retention. */
    fun deleteOlderThan(cutoff: LocalDateTime): Int
}
