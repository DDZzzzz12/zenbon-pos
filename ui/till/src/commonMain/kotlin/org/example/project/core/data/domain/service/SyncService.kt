/**
 * Synchronization service for the change-feed-driven client cache.
 *
 * Resolves change-feed entries (and forced refreshes) into the local SQLite
 * cache. All suspend methods throw PosApiException/NetworkApiException on
 * remote failure; callers wrap in runCatchingApi.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.domain.service

import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType

interface SyncService {
    /** Forced full refresh of one entity type (e.g. after a local admin edit). */
    suspend fun syncEntityByType(
        entityType: ChangeEntityType,
        reset: Boolean = false,
    )

    /** Resolve a single CREATED/UPDATED change-feed entry into local storage. */
    suspend fun syncEntityById(
        entityType: ChangeEntityType,
        entityId: Long,
    )

    /** Apply a DELETE entry to local storage. */
    suspend fun deleteLocalEntity(
        entityType: ChangeEntityType,
        entityId: String,
    )

    /** Wipe local data for one entity type. */
    fun clearLocalEntityData(entityType: ChangeEntityType)

    /** Apply an ordered change-feed batch (deduped by entity key, in seq order). */
    suspend fun applyChangeDelta(entries: List<ChangeFeedDto.EntryDto>)

    /** Full reconcile of all client-synced types (gap-recovery / first run). */
    suspend fun performCompleteSync()
}
