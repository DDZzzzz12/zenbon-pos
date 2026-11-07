/**
 * Disabled implementation of TSE Transaction Service for non-German markets.
 *
 * Provides a minimal TSE implementation for markets that do not require
 * German fiscal compliance. Most operations return null (no-op); lookups for
 * non-existent transactions throw [PosException(TSE_DISABLED, ...)] to give
 * the caller a clear signal.
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.service.TseTransactionService
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory

class DisabledTseTransactionServiceImpl : TseTransactionService {
    private val logger = LoggerFactory.getLogger(DisabledTseTransactionServiceImpl::class.java)

    init {
        logger.info("TSE Transaction Service initialized in DISABLED mode - no TSE operations will be performed")
    }

    override suspend fun startTransaction(
        processType: String,
        processData: String,
        referenceId: String?,
        clientId: String,
    ): TseTransaction? {
        logger.debug("TSE disabled - transaction not created for processType=$processType, referenceId=$referenceId")
        return null
    }

    override suspend fun finishTransaction(
        tseTransactionId: Long,
        finalProcessData: String,
        clientId: String,
    ): TseTransaction? {
        logger.debug("TSE disabled - cannot finish transaction ID=$tseTransactionId")
        return null
    }

    override suspend fun abortTransaction(
        tseTransactionId: Long,
        clientId: String,
    ): TseTransaction? {
        logger.debug("TSE disabled - cannot abort transaction ID=$tseTransactionId")
        return null
    }

    override suspend fun updateTransaction(
        tseTransactionId: Long,
        processData: String,
        clientId: String,
    ): TseTransaction? {
        logger.debug("TSE disabled - cannot update transaction ID=$tseTransactionId")
        return null
    }

    override suspend fun signTransaction(
        processType: String,
        processData: String,
        referenceId: String,
        clientId: String,
        entityType: TseEntityType,
    ): TseTransaction? {
        logger.debug("TSE disabled - quick sign not performed for processType=$processType, referenceId=$referenceId")
        return null
    }

    override suspend fun getTseTransactionById(tseTransactionId: Long): TseTransaction {
        logger.debug("TSE disabled - no transaction exists for ID=$tseTransactionId")
        throw PosException(
            code = PosErrorCode.TSE_DISABLED,
            httpStatus = HttpStatusCode.ServiceUnavailable,
            message = "TSE is disabled in current configuration; transaction $tseTransactionId not available",
        )
    }

    override suspend fun getTseTransactionsByDateRange(
        startDate: String,
        endDate: String,
        limit: Int,
        offset: Int,
    ): List<TseTransaction> {
        logger.debug("TSE disabled - returning empty transaction list for date range $startDate to $endDate")
        return emptyList()
    }

    override suspend fun validateTransaction(tseTransactionId: Long): Boolean {
        logger.debug("TSE disabled - validation always returns true for ID=$tseTransactionId")
        return true
    }
}
