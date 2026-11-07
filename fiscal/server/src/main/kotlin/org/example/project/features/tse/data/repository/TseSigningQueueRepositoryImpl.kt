/**
 * Implementation of TseSigningQueueRepository using Exposed ORM.
 *
 * Per `pos-server/CLAUDE.md`: repositories return plain data, never Result and
 * never throw PosException. Database errors are surfaced as the underlying
 * Exposed exception and translated to PosException by the calling service.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.data.repository

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.tse.database.tables.TseSigningQueueTable
import org.example.project.features.tse.domain.models.TseSigningQueue
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.repository.TseSigningQueueRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

class TseSigningQueueRepositoryImpl : TseSigningQueueRepository {
    private val logger = LoggerFactory.getLogger(TseSigningQueueRepositoryImpl::class.java)

    override suspend fun create(
        referenceId: String,
        entityType: TseEntityType,
        processType: String,
        processData: String,
        clientId: String,
        priority: Int,
        openTseTransactionNumber: Long?,
    ): TseSigningQueue {
        logger.debug("Creating TSE signing queue entry for $entityType: $referenceId")
        val entry =
            TseSigningQueue.new {
                this.referenceId = referenceId
                this.openTseTransactionNumber = openTseTransactionNumber
                this.entityType = entityType
                this.processType = processType
                this.processData = processData
                this.clientId = clientId
                this.signingStatus = TseSigningStatus.PENDING
                this.retryCount = 0
                this.maxRetries = 10
                this.createdAt = currentTimeStamp()
                this.priority = priority
            }
        logger.info("TSE signing queue entry created: ID=${entry.id.value}, $entityType: $referenceId")
        return entry
    }

    override suspend fun findById(id: Long): TseSigningQueue? {
        logger.debug("Finding TSE signing queue entry by ID: $id")
        return TseSigningQueue.findById(id)
    }

    override suspend fun findByReference(
        referenceId: String,
        entityType: TseEntityType,
    ): TseSigningQueue? {
        logger.debug("Finding TSE signing queue entry for $entityType: $referenceId")
        return TseSigningQueue
            .find {
                (TseSigningQueueTable.referenceId eq referenceId) and
                    (TseSigningQueueTable.entityType eq entityType)
            }.firstOrNull()
    }

    override suspend fun existsForReference(
        referenceId: String,
        entityType: TseEntityType,
    ): Boolean =
        !TseSigningQueueTable
            .selectAll()
            .where {
                (TseSigningQueueTable.referenceId eq referenceId) and
                    (TseSigningQueueTable.entityType eq entityType)
            }.empty()

    override suspend fun findReadyForProcessing(
        limit: Int,
        ignoreBackoff: Boolean,
    ): List<TseSigningQueue> {
        logger.debug("Finding TSE signing queue entries ready for processing (limit: $limit)")
        val now = currentTimeStamp()
        // A PROCESSING entry whose lease has expired is claimable again: the status is committed
        // BEFORE the device call, so a crash mid-signing would otherwise leave a fiscal Vorgang
        // unsigned forever — PROCESSING.canRetry() is false and nothing moves it to FAILED. See
        // TseSigningQueueRepository.PROCESSING_LEASE_MINUTES.
        val leaseExpiry =
            now
                .toInstant(TimeZone.currentSystemDefault())
                .minus(TseSigningQueueRepository.PROCESSING_LEASE_MINUTES, DateTimeUnit.MINUTE)
                .toLocalDateTime(TimeZone.currentSystemDefault())
        val retryIsDue: Op<Boolean> =
            if (ignoreBackoff) {
                Op.TRUE
            } else {
                TseSigningQueueTable.nextRetryAt.isNull() or (TseSigningQueueTable.nextRetryAt lessEq now)
            }
        return TseSigningQueue
            .find {
                (
                    (
                        (
                            (TseSigningQueueTable.signingStatus eq TseSigningStatus.PENDING) or
                                (TseSigningQueueTable.signingStatus eq TseSigningStatus.QUEUED)
                        ) and
                            retryIsDue
                    )
                ) or
                    (
                        (TseSigningQueueTable.signingStatus eq TseSigningStatus.PROCESSING) and
                            (
                                TseSigningQueueTable.lastAttemptAt.isNull() or
                                    (TseSigningQueueTable.lastAttemptAt less leaseExpiry)
                            )
                    )
            }.orderBy(
                // E2E R2-4: oldest first. The priority column is still written but no longer read
                // here: live Vorgänge never pass through the queue, so "customer waiting" never
                // applied, and signing a backlog bill-first put a bill before the orders it settles
                // in the TSE log.
                TseSigningQueueTable.createdAt to SortOrder.ASC,
                TseSigningQueueTable.id to SortOrder.ASC,
            ).limit(limit)
            .toList()
    }

    override suspend fun findOldestAwaitingSigning(): TseSigningQueue? =
        TseSigningQueue
            .find {
                (TseSigningQueueTable.signingStatus eq TseSigningStatus.PENDING) or
                    (TseSigningQueueTable.signingStatus eq TseSigningStatus.QUEUED)
            }.orderBy(TseSigningQueueTable.createdAt to SortOrder.ASC, TseSigningQueueTable.id to SortOrder.ASC)
            .limit(1)
            .firstOrNull()

    override suspend fun findByStatus(
        status: TseSigningStatus,
        limit: Int,
        offset: Int,
    ): List<TseSigningQueue> =
        TseSigningQueue
            .find {
                TseSigningQueueTable.signingStatus eq status
            }.orderBy(TseSigningQueueTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .toList()

    override suspend fun findByEntityType(
        entityType: TseEntityType,
        limit: Int,
        offset: Int,
    ): List<TseSigningQueue> =
        TseSigningQueue
            .find {
                TseSigningQueueTable.entityType eq entityType
            }.orderBy(TseSigningQueueTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .toList()

    override suspend fun updateStatus(
        id: Long,
        status: TseSigningStatus,
        error: String?,
        errorCode: String?,
    ): TseSigningQueue? {
        val entry = TseSigningQueue.findById(id) ?: return null
        entry.signingStatus = status
        error?.let { entry.lastError = it }
        errorCode?.let { entry.lastErrorCode = it }
        logger.info("TSE signing queue entry updated: ID=$id, status=$status")
        return entry
    }

    override suspend fun markAsProcessing(id: Long): TseSigningQueue? {
        val entry = TseSigningQueue.findById(id) ?: return null
        entry.signingStatus = TseSigningStatus.PROCESSING
        entry.lastAttemptAt = currentTimeStamp()
        return entry
    }

    override suspend fun markAsSigned(
        id: Long,
        tseTransaction: TseTransaction,
    ): TseSigningQueue? {
        val entry = TseSigningQueue.findById(id) ?: return null
        entry.signingStatus = TseSigningStatus.SIGNED
        entry.signedAt = currentTimeStamp()
        entry.tseTransaction = tseTransaction
        entry.lastError = null
        entry.lastErrorCode = null
        return entry
    }

    override suspend fun markForRetry(
        id: Long,
        error: String,
        errorCode: String?,
        nextRetryAt: LocalDateTime,
    ): TseSigningQueue? {
        val entry = TseSigningQueue.findById(id) ?: return null
        entry.signingStatus = TseSigningStatus.QUEUED
        entry.retryCount = entry.retryCount + 1
        entry.lastError = error
        entry.lastErrorCode = errorCode
        entry.nextRetryAt = nextRetryAt
        return entry
    }

    override suspend fun markAsFailed(
        id: Long,
        error: String,
        errorCode: String?,
    ): TseSigningQueue? {
        val entry = TseSigningQueue.findById(id) ?: return null
        entry.signingStatus = TseSigningStatus.FAILED
        entry.lastError = error
        entry.lastErrorCode = errorCode
        logger.warn("TSE signing queue entry marked as failed: ID=$id, error=$error")
        return entry
    }

    override suspend fun cancel(id: Long): TseSigningQueue? {
        val entry = TseSigningQueue.findById(id) ?: return null
        entry.signingStatus = TseSigningStatus.CANCELLED
        return entry
    }

    override suspend fun countByStatus(status: TseSigningStatus?): Long =
        if (status != null) {
            TseSigningQueueTable
                .selectAll()
                .where { TseSigningQueueTable.signingStatus eq status }
                .count()
        } else {
            TseSigningQueueTable.selectAll().count()
        }

    override suspend fun getStatusCounts(): Map<TseSigningStatus, Long> =
        TseSigningStatus.entries.associateWith { status ->
            TseSigningQueueTable
                .selectAll()
                .where { TseSigningQueueTable.signingStatus eq status }
                .count()
        }

    override suspend fun deleteOldEntries(
        olderThan: LocalDateTime,
        statuses: List<TseSigningStatus>,
    ): Int {
        val deletedCount =
            TseSigningQueueTable.deleteWhere {
                (createdAt less olderThan) and (signingStatus inList statuses)
            }
        logger.info("Deleted $deletedCount old TSE signing queue entries")
        return deletedCount
    }
}
