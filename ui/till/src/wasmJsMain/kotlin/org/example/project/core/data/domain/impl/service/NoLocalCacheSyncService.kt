package org.example.project.core.data.domain.impl.service

import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType

/**
 * [SyncService] for the browser preview, where there is nothing to synchronise.
 *
 * Every method on [SyncService] writes to, or clears, the local SQLite cache. The wasm build has no
 * such cache — the `ApiClient*` providers read straight from pos-server on every call — so doing
 * nothing here is the accurate implementation rather than a stub: there is no state to fall out of
 * date, and a change-feed entry has nothing to be applied to.
 *
 * Note what this deliberately does *not* cover: no method here sends anything to the server, so
 * no-oping cannot discard an operator's write. Any service that does perform server writes must not
 * be neutered this way.
 */
class NoLocalCacheSyncService : SyncService {
    override suspend fun syncEntityByType(
        entityType: ChangeEntityType,
        reset: Boolean,
    ) = Unit

    override suspend fun syncEntityById(
        entityType: ChangeEntityType,
        entityId: Long,
    ) = Unit

    override suspend fun deleteLocalEntity(
        entityType: ChangeEntityType,
        entityId: String,
    ) = Unit

    override fun clearLocalEntityData(entityType: ChangeEntityType) = Unit

    override suspend fun applyChangeDelta(entries: List<ChangeFeedDto.EntryDto>) = Unit

    override suspend fun performCompleteSync() = Unit
}
