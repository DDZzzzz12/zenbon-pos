package org.example.project.features.audit.data.service

import org.example.project.core.domain.dto.sync.ChangeFeedDto
import org.example.project.features.audit.domain.repository.EntryLogRepository
import org.example.project.features.audit.domain.service.ChangeFeedService
import org.example.project.utils.dbQuery

class ChangeFeedServiceImpl(
    private val repository: EntryLogRepository,
) : ChangeFeedService {
    override suspend fun getChanges(
        sinceSeq: Long,
        limit: Int,
    ): ChangeFeedDto.ResponseDto =
        dbQuery {
            val rows = repository.getChangesSince(sinceSeq, limit.coerceIn(1, 1000))
            val entries =
                rows.map {
                    ChangeFeedDto.EntryDto(
                        seq = it.id.value,
                        entityType = it.type,
                        entityId = it.entityId,
                        operation = it.operation,
                    )
                }
            ChangeFeedDto.ResponseDto(
                entries = entries,
                highWaterSeq = repository.maxClientSyncedSeq() ?: 0L,
                minAvailableSeq = repository.minSeq() ?: 0L,
            )
        }
}
