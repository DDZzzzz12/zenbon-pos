/**
 * Interface for TSE signing queue API operations.
 *
 * Stage C Batch 8: throws-style.
 *
 * Implementations:
 * - [org.example.project.core.data.remote.impl.KtorTseQueueApiClient] — HTTP client
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.tse.TseSigningStatus

interface TseQueueApiClient {
    suspend fun getQueueStatus(): TseSigningQueueDto.QueueStatusDto

    suspend fun getQueueEntries(
        status: TseSigningStatus? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): List<TseSigningQueueDto.QueueEntryResponseDto>

    suspend fun retryEntry(entryId: Long): TseSigningQueueDto.EntryOperationResponseDto

    /**
     * Abandons signing for [entryId]. [reason] is mandatory and rejected by the server when blank:
     * the Vorgang stays permanently unsigned, so the audit trail must say why (§146a AO).
     */
    suspend fun cancelEntry(
        entryId: Long,
        reason: String,
    ): TseSigningQueueDto.EntryOperationResponseDto

    suspend fun pauseService(): TseSigningQueueDto.ServiceControlResponseDto

    suspend fun resumeService(): TseSigningQueueDto.ServiceControlResponseDto

    suspend fun triggerRetry(): TseSigningQueueDto.TriggerRetryResponseDto

    suspend fun cleanupOldEntries(olderThanDays: Int = 30): TseSigningQueueDto.CleanupResultDto
}
