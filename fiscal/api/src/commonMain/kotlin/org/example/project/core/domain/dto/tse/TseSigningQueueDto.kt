/**
 * Container object for TSE signing queue DTOs used by both client and server.
 *
 * Provides shared data transfer objects for queue monitoring and management
 * in the TSE Settings UI. These DTOs are used for:
 * - Queue status overview (counts, service status)
 * - Queue entry details (individual signing queue items)
 * - Service control responses (pause/resume operations)
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.tse

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus

/**
 * Container for TSE signing queue DTOs shared between client and server.
 */
object TseSigningQueueDto {
    /**
     * DTO for queue status summary.
     *
     * Provides an overview of the signing queue state including:
     * - Entry counts by status
     * - TSE availability
     * - Retry service status
     * - Oldest pending entry age
     */
    @Serializable
    data class QueueStatusDto(
        /** Total number of entries in the queue */
        val totalEntries: Long,
        /** Number of entries awaiting initial signing */
        val pendingCount: Long,
        /** Number of entries currently being processed */
        val processingCount: Long,
        /** Number of entries queued for retry (Nachsignieren) */
        val queuedCount: Long,
        /** Number of successfully signed entries */
        val signedCount: Long,
        /** Number of entries that failed after max retries */
        val failedCount: Long,
        /** Number of cancelled entries */
        val cancelledCount: Long,
        /** Whether TSE device is currently available */
        val tseAvailable: Boolean,
        /** Whether the background retry service is running */
        val retryServiceRunning: Boolean,
        /** Human-readable age of oldest pending entry (e.g., "2h 15m") */
        val oldestPendingAge: String?,
        /** Average retry count across all retried entries */
        val averageRetryCount: Double,
    )

    /**
     * Response DTO for a TSE signing queue entry.
     *
     * Contains all information about a single queue entry for
     * display in the UI and management operations.
     */
    @Serializable
    data class QueueEntryResponseDto(
        /** Unique queue entry ID */
        val id: Long,
        /** Reference to the original entity (UUID for Bills, Long ID for others) */
        val referenceId: String,
        /** Type of entity being signed (BILL, ORDER, etc.) */
        val entityType: TseEntityType,
        /** DSFinV-K process type (e.g., "Kassenbeleg-V1") */
        val processType: String,
        /** TSE client ID used for signing */
        val clientId: String,
        /** Current signing status */
        val signingStatus: TseSigningStatus,
        /** Number of retry attempts made */
        val retryCount: Int,
        /** Maximum allowed retry attempts */
        val maxRetries: Int,
        /** Last error message if any */
        val lastError: String?,
        /** Last error code if any */
        val lastErrorCode: String?,
        /** When the entry was created (ISO-8601 format) */
        val createdAt: String,
        /** When the last signing attempt was made (ISO-8601 format) */
        val lastAttemptAt: String?,
        /** When the entry was successfully signed (ISO-8601 format) */
        val signedAt: String?,
        /** When the next retry is scheduled (ISO-8601 format) */
        val nextRetryAt: String?,
        /** Processing priority (1 = highest) */
        val priority: Int,
        /** Associated TSE transaction ID if signed */
        val tseTransactionId: Long?,
    )

    /**
     * Response DTO for pause/resume service operations.
     */
    @Serializable
    data class ServiceControlResponseDto(
        /** Whether the operation succeeded */
        val success: Boolean,
        /** Current service running state after operation */
        val serviceRunning: Boolean,
        /** Human-readable result message */
        val message: String,
    )

    /**
     * Response DTO for cleanup operation result.
     */
    @Serializable
    data class CleanupResultDto(
        /** Number of entries deleted */
        val deletedCount: Int,
        /** The age threshold used for cleanup */
        val olderThanDays: Int,
    )

    /**
     * Response DTO for manual retry trigger operation.
     */
    @Serializable
    data class TriggerRetryResponseDto(
        /** Whether the operation succeeded */
        val success: Boolean,
        /** Number of entries processed */
        val processedCount: Int,
        /** Number of entries successfully signed */
        val successCount: Int,
        /** Number of entries that failed */
        val failureCount: Int,
        /** Human-readable result message */
        val message: String,
    )

    /**
     * Response DTO for single entry retry/cancel operations.
     */
    @Serializable
    data class EntryOperationResponseDto(
        /** Whether the operation succeeded */
        val success: Boolean,
        /** The affected queue entry ID */
        val entryId: Long,
        /** New status after operation */
        val newStatus: TseSigningStatus?,
        /** Human-readable result message */
        val message: String,
    )

    /**
     * Request DTO for abandoning a queued signature.
     *
     * The reason travels in the body rather than a query parameter: it is operator-entered free
     * text that ends up in the audit trail, and query strings are recorded verbatim by proxies and
     * access logs.
     */
    @Serializable
    data class CancelEntryRequestDto(
        /** Why signing is being abandoned. Mandatory — the server rejects a blank value. */
        val reason: String,
    )
}
