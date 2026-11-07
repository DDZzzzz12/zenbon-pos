/**
 * Exposed DAO entity for TSE signing queue.
 *
 * Provides object-relational mapping for TSE signing queue entries with proper
 * relationship management and type-safe database operations. Supports the
 * "Nachsignieren" (late signing) feature for German KassenSichV compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.domain.models

import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * TSE Signing Queue entity for tracking pending TSE signatures.
 *
 * Represents a queue entry for a transaction that needs to be signed
 * by the TSE. Supports automatic retry, priority ordering, and
 * comprehensive status tracking.
 */
class TseSigningQueue(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<TseSigningQueue>(TseSigningQueueTable)

    /**
     * Reference ID of the entity that needs signing.
     * For Bills: UUID string, For Orders/Kassenbuch: Long ID as string
     */
    var referenceId by TseSigningQueueTable.referenceId

    /**
     * See [TseSigningQueueTable.openTseTransactionNumber] (audit TSER-12) — vestigial since the
     * signer resumes by (reference, entity type); nothing reads it.
     */
    var openTseTransactionNumber by TseSigningQueueTable.openTseTransactionNumber

    /**
     * Type of entity that needs signing.
     */
    var entityType by TseSigningQueueTable.entityType

    /**
     * DSFinV-K process type for TSE signing.
     */
    var processType by TseSigningQueueTable.processType

    /**
     * JSON process data to be signed by TSE.
     */
    var processData by TseSigningQueueTable.processData

    /**
     * TSE client ID used for signing.
     */
    var clientId by TseSigningQueueTable.clientId

    /**
     * Current signing status.
     */
    var signingStatus by TseSigningQueueTable.signingStatus

    /**
     * Number of retry attempts made.
     */
    var retryCount by TseSigningQueueTable.retryCount

    /**
     * Maximum number of retries before marking as FAILED.
     */
    var maxRetries by TseSigningQueueTable.maxRetries

    /**
     * Last error message if signing failed.
     */
    var lastError by TseSigningQueueTable.lastError

    /**
     * Last error code for categorization.
     */
    var lastErrorCode by TseSigningQueueTable.lastErrorCode

    /**
     * Timestamp when the queue entry was created.
     */
    var createdAt by TseSigningQueueTable.createdAt

    /**
     * Timestamp of the last signing attempt.
     */
    var lastAttemptAt by TseSigningQueueTable.lastAttemptAt

    /**
     * Timestamp when signing was completed successfully.
     */
    var signedAt by TseSigningQueueTable.signedAt

    /**
     * Timestamp for the next retry attempt.
     */
    var nextRetryAt by TseSigningQueueTable.nextRetryAt

    /**
     * Priority for queue processing (lower = higher priority).
     */
    var priority by TseSigningQueueTable.priority

    /**
     * Reference to the TSE transaction after successful signing.
     */
    var tseTransaction by TseTransaction optionalReferencedOn TseSigningQueueTable.tseTransactionId

    /**
     * Checks if this queue entry can be retried.
     *
     * @return true if retry is allowed based on status and retry count
     */
    fun canRetry(): Boolean = signingStatus.canRetry() && retryCount < maxRetries

    /**
     * Checks if this entry has exceeded the maximum retry limit.
     *
     * @return true if max retries exceeded
     */
    fun isMaxRetriesExceeded(): Boolean = retryCount >= maxRetries

    /**
     * Calculates the next retry delay using exponential backoff.
     *
     * Backoff formula: min(baseDelay * 2^retryCount, maxDelay)
     * - Base delay: 60 seconds (1 minute)
     * - Max delay: 300 seconds (5 minutes)
     *
     * @return Delay in seconds until next retry
     */
    fun calculateNextRetryDelay(): Long {
        val baseDelaySeconds = 60L
        val maxDelaySeconds = 300L
        val exponentialDelay = baseDelaySeconds * (1L shl minOf(retryCount, 4))
        return minOf(exponentialDelay, maxDelaySeconds)
    }
}
