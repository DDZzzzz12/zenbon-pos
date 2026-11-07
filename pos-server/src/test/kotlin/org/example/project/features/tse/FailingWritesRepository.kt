package org.example.project.features.tse

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.repository.TseTransactionRepository

/**
 * A repository whose creates and/or updates fail like a lost database connection.
 *
 * The failure is PERMANENT for the lifetime of this instance rather than a one-shot counter,
 * because `dbQuery` runs inside Exposed, which RETRIES a transaction that throws
 * [java.sql.SQLException] (three attempts by default). A fake that failed only once would be
 * retried straight into success, and the test would silently prove nothing. A caller that must
 * survive the failure uses a second, real repository — which is what a genuine retry after an
 * outage looks like.
 */
class FailingWritesRepository(
    private val delegate: TseTransactionRepository,
    private val failCreates: Boolean = false,
    private val failUpdates: Boolean = false,
) : TseTransactionRepository by delegate {
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
        if (failCreates) throw java.sql.SQLException("connection lost")
        return delegate.create(
            tseSerialNumber = tseSerialNumber,
            transactionNumber = transactionNumber,
            processType = processType,
            processDataHash = processDataHash,
            signature = signature,
            startTime = startTime,
            clientId = clientId,
            tseMode = tseMode,
            tseStartTimestamp = tseStartTimestamp,
            referenceId = referenceId,
            entityType = entityType,
        )
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
        if (failUpdates) throw java.sql.SQLException("connection lost")
        return delegate.update(
            tseTransactionId = tseTransactionId,
            processDataHash = processDataHash,
            signature = signature,
            endTime = endTime,
            exportedFileReference = exportedFileReference,
            tseTimestamp = tseTimestamp,
            signatureCounter = signatureCounter,
            signatureAlgorithm = signatureAlgorithm,
            publicKey = publicKey,
            certificate = certificate,
        )
    }
}
