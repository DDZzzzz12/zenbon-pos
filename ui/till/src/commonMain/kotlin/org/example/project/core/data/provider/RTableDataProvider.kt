/**
 * Data provider interface for restaurant table read operations.
 *
 * Stage C Batch 9: flipped from `Result<T, Error>` to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider

import org.example.project.core.data.models.RTable

/**
 * Read-only data provider for restaurant tables.
 */
interface RTableDataProvider {
    /** Gets all restaurant tables. */
    suspend fun getAllRTables(): List<RTable>

    /** Gets tables filtered by location type ID. */
    suspend fun getRTablesByLocationTypeId(
        locationTypeId: Long,
        includeInactive: Boolean = false,
    ): List<RTable>

    /** Gets a specific table by ID, or null if not found. */
    suspend fun getRTableById(id: Long): RTable?

    /**
     * Searches tables by partial table number match.
     * Results are ranked: exact match > starts with > contains, limited to 5.
     */
    suspend fun searchRTables(query: String): List<RTable>

    /** Fetch this location type's tables from the server, replace them in the cache, and signal. */
    suspend fun refresh(
        locationTypeId: Long,
        includeInactive: Boolean = false,
    )

    /** Refresh once per app session for this location type (no-op if already seeded). */
    suspend fun ensureSeeded(
        locationTypeId: Long,
        includeInactive: Boolean = false,
    )

    /** Forget which location types have been seeded this session (e.g. on logout), so the next ensureSeeded re-fetches. */
    fun resetSeed()
}
