package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.TseQueueApiClient
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.tse.TseSigningStatus

class FakeTseQueueApiClient(
    private val status: () -> TseSigningQueueDto.QueueStatusDto,
) : TseQueueApiClient {
    override suspend fun getQueueStatus(): TseSigningQueueDto.QueueStatusDto = status()

    override suspend fun getQueueEntries(
        status: TseSigningStatus?,
        limit: Int,
        offset: Int,
    ): List<TseSigningQueueDto.QueueEntryResponseDto> = throw NotImplementedError()

    override suspend fun retryEntry(entryId: Long): TseSigningQueueDto.EntryOperationResponseDto = throw NotImplementedError()

    override suspend fun cancelEntry(
        entryId: Long,
        reason: String,
    ): TseSigningQueueDto.EntryOperationResponseDto = throw NotImplementedError()

    override suspend fun pauseService(): TseSigningQueueDto.ServiceControlResponseDto = throw NotImplementedError()

    override suspend fun resumeService(): TseSigningQueueDto.ServiceControlResponseDto = throw NotImplementedError()

    override suspend fun triggerRetry(): TseSigningQueueDto.TriggerRetryResponseDto = throw NotImplementedError()

    override suspend fun cleanupOldEntries(olderThanDays: Int): TseSigningQueueDto.CleanupResultDto = throw NotImplementedError()
}
