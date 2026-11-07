package org.example.project.testutils.fakes

import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.core.domain.enums.websocket.ChangeEntityType

/**
 * No-op [SyncService] for tests that construct components depending on it but do
 * not exercise synchronization. Calls to [applyChangeDelta] are recorded for
 * assertions; the remaining methods are inert.
 */
open class FakeSyncService : SyncService {
    /** Records each batch passed to [applyChangeDelta], in call order. */
    val appliedDeltas: MutableList<List<ChangeFeedDto.EntryDto>> = mutableListOf()

    override suspend fun syncEntityByType(
        entityType: ChangeEntityType,
        reset: Boolean,
    ) {}

    override suspend fun syncEntityById(
        entityType: ChangeEntityType,
        entityId: Long,
    ) {}

    override suspend fun deleteLocalEntity(
        entityType: ChangeEntityType,
        entityId: String,
    ) {}

    override fun clearLocalEntityData(entityType: ChangeEntityType) {}

    override suspend fun applyChangeDelta(entries: List<ChangeFeedDto.EntryDto>) {
        appliedDeltas.add(entries)
    }

    override suspend fun performCompleteSync() {}
}
