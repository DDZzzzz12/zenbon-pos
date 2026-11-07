/**
 * Container object for server-only TSE signing queue DTOs.
 *
 * Shared DTOs (QueueStatusDto, QueueEntryResponseDto, ServiceControlResponseDto,
 * CleanupResultDto, TriggerRetryResponseDto, EntryOperationResponseDto) are in the
 * shared module at [org.example.project.core.domain.dto.tse.TseSigningQueueDto].
 *
 * This file contains:
 * - Server-only DTOs (batch processing, enqueue requests, list filters)
 * - Extension function to convert [TseSigningQueue] entity to shared [QueueEntryResponseDto]
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.domain.dto

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.core.domain.dto.tse.TseSigningQueueDto as SharedQueueDto

/**
 * Container for server-only TSE signing queue DTOs.
 *
 * For shared DTOs used by both client and server, see
 * [org.example.project.core.domain.dto.tse.TseSigningQueueDto].
 */
object TseSigningQueueDto {
    /**
     * DTO for batch processing result.
     */
    @Serializable
    data class BatchProcessingResultDto(
        val processedCount: Int,
        val successCount: Int,
        val failureCount: Int,
        val queuedForRetryCount: Int,
        val skippedCount: Int,
        val processingTimeMs: Long,
        val errors: List<ProcessingErrorDto>,
    )

    /**
     * DTO for individual processing error in batch.
     */
    @Serializable
    data class ProcessingErrorDto(
        val queueEntryId: Long,
        val referenceId: String,
        val entityType: TseEntityType,
        val errorCode: String?,
        val errorMessage: String,
    )

    /**
     * Request DTO for manually enqueuing a signing request.
     */
    @Serializable
    data class EnqueueRequestDto(
        val referenceId: String,
        val entityType: TseEntityType,
        val processType: String,
        val processData: String,
        val clientId: String,
        val priority: Int? = null,
    ) {
        /**
         * Validates the request parameters.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(referenceId.isNotBlank()) { "Reference ID is required" }
            require(processType.isNotBlank()) { "Process type is required" }
            require(processData.isNotBlank()) { "Process data is required" }
            require(clientId.isNotBlank()) { "Client ID is required" }
            priority?.let {
                require(it in 1..10) { "Priority must be between 1 and 10" }
            }
        }
    }

    /**
     * Request DTO for listing queue entries with filters.
     */
    @Serializable
    data class ListEntriesRequestDto(
        val status: TseSigningStatus? = null,
        val entityType: TseEntityType? = null,
        val limit: Int = 100,
        val offset: Int = 0,
    ) {
        /**
         * Validates the request parameters.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(limit in 1..1000) { "Limit must be between 1 and 1000" }
            require(offset >= 0) { "Offset must be non-negative" }
        }
    }
}

/**
 * Converts a [TseSigningQueue] entity to the shared [SharedQueueDto.QueueEntryResponseDto].
 *
 * This extension function replaces the `fromEntity()` companion that previously
 * lived on the server-side duplicate of QueueEntryResponseDto.
 */
fun TseSigningQueue.toQueueEntryResponseDto(): SharedQueueDto.QueueEntryResponseDto =
    SharedQueueDto.QueueEntryResponseDto(
        id = this.id.value,
        referenceId = this.referenceId,
        entityType = this.entityType,
        processType = this.processType,
        clientId = this.clientId,
        signingStatus = this.signingStatus,
        retryCount = this.retryCount,
        maxRetries = this.maxRetries,
        lastError = this.lastError,
        lastErrorCode = this.lastErrorCode,
        createdAt = this.createdAt.toString(),
        lastAttemptAt = this.lastAttemptAt?.toString(),
        signedAt = this.signedAt?.toString(),
        nextRetryAt = this.nextRetryAt?.toString(),
        priority = this.priority,
        tseTransactionId = this.tseTransaction?.id?.value,
    )
