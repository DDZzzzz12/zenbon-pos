package org.example.project.features.tse.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.utils.formatter.TseSerialFormat
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

/**
 * Implementation of TseTransactionRepository using Exposed ORM.
 *
 * Per `pos-server/CLAUDE.md`: repositories return plain data, never Result and
 * never throw PosException. Database errors are surfaced as the underlying
 * Exposed exception and translated to PosException by the calling service.
 *
 * @since 1.0.0
 */
class TseTransactionRepositoryImpl : TseTransactionRepository {
    private val logger = LoggerFactory.getLogger(TseTransactionRepositoryImpl::class.java)

    override suspend fun create(
        tseSerialNumber: String?,
        transactionNumber: Long,
        processType: String?,
        processDataHash: String?,
        signature: String?,
        startTime: LocalDateTime?,
        clientId: String?,
        tseMode: TseMode?,
        tseStartTimestamp: Long?,
        referenceId: String?,
        entityType: String?,
    ): TseTransaction {
        val actualTseMode = tseMode ?: TseMode.SIMULATION
        logger.debug("Creating TSE transaction for processType=$processType, txNum=$transactionNumber, mode=$actualTseMode")
        val tseTransaction =
            TseTransaction.new {
                // TR-03153-1 §9.3.2: the serial identifying the TSE must be the hex string. Done
                // here rather than at each call site so every writer is covered, and written to
                // both columns so a new row needs no fallback (V82).
                val hex = TseSerialFormat.normalizeToHex(tseSerialNumber)
                this.tseSerialNumber = hex
                // A SIMULATION row has no device, so it has no TSE serial to identify: its
                // "SIM-TSE-001" placeholder (Base64, not 32 bytes) used to land in this hex column
                // verbatim (real-TSE QA rt12, finding e). Null instead. The raw column keeps the
                // placeholder, so serialHex() and the month-end simulated-signature check, which
                // both fall back to it, read exactly what they read before.
                this.tseSerialHex = if (actualTseMode == TseMode.SIMULATION) null else hex
                this.transactionNumber = transactionNumber
                this.processType = processType
                this.processDataHash = processDataHash
                this.signature = signature
                this.startTime = startTime
                this.clientId = clientId
                this.referenceId = referenceId
                this.entityType = entityType
                this.tseMode = actualTseMode
                this.tseStartTimestamp = tseStartTimestamp
            }
        logger.info("TSE transaction created: ID=${tseTransaction.id.value}, txNum=$transactionNumber")
        return tseTransaction
    }

    override suspend fun findCurrentByReference(
        referenceId: String,
        entityType: String,
    ): TseTransaction? =
        TseTransaction
            .find {
                (TseTransactionTable.referenceId eq referenceId) and
                    (TseTransactionTable.entityType eq entityType) and
                    (TseTransactionTable.state neq TseTransactionState.SUPERSEDED)
            }.orderBy(TseTransactionTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()

    override suspend fun findLegacySigned(
        referenceId: String,
        processType: String,
        processData: String,
    ): TseTransaction? =
        TseTransaction
            .find {
                (TseTransactionTable.referenceId eq referenceId) and
                    TseTransactionTable.entityType.isNull() and
                    TseTransactionTable.signature.isNotNull() and
                    (TseTransactionTable.processType eq processType) and
                    (TseTransactionTable.processDataHash eq processData)
            }.orderBy(TseTransactionTable.id to SortOrder.ASC)
            .limit(1)
            .firstOrNull()

    override suspend fun markSuperseded(tseTransactionId: Long): TseTransaction? =
        TseTransaction.findById(tseTransactionId)?.apply { state = TseTransactionState.SUPERSEDED }

    override suspend fun linkReplacement(
        tseTransactionId: Long,
        replacementId: Long,
    ) {
        TseTransaction.findById(tseTransactionId)?.supersededById = replacementId
    }

    override suspend fun update(
        tseTransactionId: Long,
        processDataHash: String?,
        signature: String?,
        endTime: LocalDateTime?,
        exportedFileReference: String?,
        tseTimestamp: Long?,
        signatureCounter: Long?,
        signatureAlgorithm: String?,
        publicKey: String?,
        certificate: String?,
    ): TseTransaction? {
        logger.debug("Updating TSE transaction: $tseTransactionId")
        val tseTransaction =
            TseTransaction.findById(tseTransactionId)?.apply {
                processDataHash?.let { this.processDataHash = it }
                signature?.let { this.signature = it }
                endTime?.let { this.endTime = it }
                exportedFileReference?.let { this.exportedFileReference = it }
                tseTimestamp?.let { this.tseTimestamp = it }
                signatureCounter?.let { this.signatureCounter = it }
                signatureAlgorithm?.let { this.signatureAlgorithm = it }
                publicKey?.let { this.publicKey = it }
                certificate?.let { this.certificate = it }
            }
        if (tseTransaction != null) {
            logger.info("TSE transaction updated: ID=$tseTransactionId, sigCounter=$signatureCounter")
        }
        return tseTransaction
    }

    override suspend fun findById(tseTransactionId: Long): TseTransaction? {
        logger.debug("Finding TSE transaction by ID: $tseTransactionId")
        return TseTransaction.findById(tseTransactionId)
    }

    override suspend fun findByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int,
        offset: Int,
    ): List<TseTransaction> {
        logger.debug("Finding TSE transactions by date range: $startDate to $endDate")
        return TseTransaction
            .find {
                (TseTransactionTable.startTime greaterEq startDate) and
                    (TseTransactionTable.startTime lessEq endDate)
            }.limit(limit)
            .offset(offset.toLong())
            .toList()
    }

    override suspend fun findByProcessType(
        processType: String,
        limit: Int,
        offset: Int,
    ): List<TseTransaction> {
        logger.debug("Finding TSE transactions by processType=$processType")
        return TseTransaction
            .find {
                TseTransactionTable.processType eq processType
            }.limit(limit)
            .offset(offset.toLong())
            .toList()
    }

    override suspend fun findByClientId(
        clientId: String,
        limit: Int,
        offset: Int,
    ): List<TseTransaction> {
        logger.debug("Finding TSE transactions by clientId=$clientId")
        return TseTransaction
            .find {
                TseTransactionTable.clientId eq clientId
            }.limit(limit)
            .offset(offset.toLong())
            .toList()
    }

    override suspend fun findCompleted(
        limit: Int,
        offset: Int,
    ): List<TseTransaction> {
        logger.debug("Finding completed TSE transactions")
        return TseTransaction
            .find {
                TseTransactionTable.endTime.isNotNull()
            }.limit(limit)
            .offset(offset.toLong())
            .toList()
    }

    override suspend fun findPending(
        limit: Int,
        offset: Int,
    ): List<TseTransaction> {
        logger.debug("Finding pending TSE transactions")
        return TseTransaction
            .find {
                TseTransactionTable.endTime.isNull()
            }.limit(limit)
            .offset(offset.toLong())
            .toList()
    }

    override suspend fun count(): Long = TseTransactionTable.selectAll().count()

    override suspend fun countByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): Long =
        TseTransactionTable
            .selectAll()
            .where {
                (TseTransactionTable.startTime greaterEq startDate) and
                    (TseTransactionTable.startTime lessEq endDate)
            }.count()

    override suspend fun getLatestTransactionNumber(): Long? =
        TseTransactionTable
            .selectAll()
            .orderBy(TseTransactionTable.transactionNumber, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(TseTransactionTable.transactionNumber)

    override suspend fun delete(tseTransactionId: Long): Boolean {
        logger.debug("Deleting TSE transaction: $tseTransactionId")
        val transaction = TseTransaction.findById(tseTransactionId) ?: return false
        transaction.delete()
        logger.info("TSE transaction deleted: ID=$tseTransactionId")
        return true
    }

    override suspend fun findForExport(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        completedOnly: Boolean,
    ): List<TseTransaction> {
        logger.debug("Finding TSE transactions for export: $startDate to $endDate, completedOnly=$completedOnly")
        val query =
            if (completedOnly) {
                TseTransaction.find {
                    (TseTransactionTable.startTime greaterEq startDate) and
                        (TseTransactionTable.startTime lessEq endDate) and
                        TseTransactionTable.endTime.isNotNull()
                }
            } else {
                TseTransaction.find {
                    (TseTransactionTable.startTime greaterEq startDate) and
                        (TseTransactionTable.startTime lessEq endDate)
                }
            }
        return query.orderBy(TseTransactionTable.startTime to SortOrder.ASC).toList()
    }
}
