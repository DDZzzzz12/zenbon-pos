/**
 * Data provider interface for room element read operations.
 *
 * Stage C Batch 9: flipped from `Result<T, Error>` to throws-style.
 *
 * @since 1.0.0
 */
package org.example.project.core.data.provider

import org.example.project.core.data.models.RoomElement

/**
 * Read-only data provider for room elements.
 */
interface RoomElementDataProvider {
    /** Gets all room elements. */
    suspend fun getAllElements(): List<RoomElement>

    /** Gets room elements filtered by location type. */
    suspend fun getElementsByLocationType(locationType: String): List<RoomElement>

    /** Fetch this location type's room elements from the server, replace them in the cache, and signal. */
    suspend fun refresh(locationTypeId: Long)

    /** Refresh once per app session for this location type (no-op if already seeded). */
    suspend fun ensureSeeded(locationTypeId: Long)

    /** Forget which location types have been seeded this session (e.g. on logout), so the next ensureSeeded re-fetches. */
    fun resetSeed()
}
