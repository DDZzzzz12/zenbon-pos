package org.example.project.features.tse.data.service

import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.features.bill.domain.service.SaleSigningAttempt
import org.example.project.features.bill.domain.service.SaleSigningGateway
import org.example.project.features.bill.domain.service.SaleSigningKind
import org.example.project.features.bill.domain.service.SaleSigningRequest
import org.example.project.features.tse.domain.TseSignOutcome
import org.example.project.features.tse.domain.TseSigningOutcomeResolver
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.repository.TseLateSigningRepository
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.tse.domain.service.TseTransactionService
import org.example.project.features.tse.domain.toLateSigningCause
import org.example.project.infrastructure.errors.PosException

/**
 * The fiscal side of [SaleSigningGateway]: signs through [TseServiceFactory], resolves the outcome with
 * [TseSigningOutcomeResolver] and queues through [TseSigningQueueService] — the sequence the order,
 * cancellation and bill services used to run inline.
 */
class TseSaleSigningGateway(
    private val tseServiceFactory: TseServiceFactory,
    private val tseSigningQueueService: TseSigningQueueService,
    private val configManager: IShopConfigurationManager,
    private val lateSigningRepository: TseLateSigningRepository,
) : SaleSigningGateway {
    override suspend fun attempt(request: SaleSigningRequest): SaleSigningAttempt {
        val mode = configManager.getTseMode()
        val failureBehavior = configManager.getTseConfig().failureBehavior
        return attempt(request, mode, failureBehavior)
    }

    override suspend fun attempt(
        request: SaleSigningRequest,
        mode: TseMode,
        failureBehavior: TseFailureBehavior,
    ): SaleSigningAttempt {
        var signedTx: TseTransaction? = null
        val outcome =
            try {
                val tseTx =
                    tseServiceFactory.getTseService().signTransaction(
                        processType = request.kind.processType(),
                        processData = request.processData,
                        referenceId = request.referenceId,
                        clientId = request.clientId,
                        entityType = request.entityType,
                    )
                signedTx = tseTx
                if (tseTx == null) TseSignOutcome.NoSignature else TseSignOutcome.Signed
            } catch (e: PosException) {
                TseSignOutcome.Failed(e)
            }

        val decision = TseSigningOutcomeResolver.resolve(mode, failureBehavior, outcome)
        val cause = if (decision.enqueue) outcome.toLateSigningCause() else null
        return SaleSigningAttempt(
            transaction = signedTx,
            status = decision.status,
            enqueue = decision.enqueue,
            rethrow = decision.rethrow,
            lateReason = cause?.first,
            lateReasonText = cause?.second,
        )
    }

    override suspend fun enqueue(
        request: SaleSigningRequest,
        attempt: SaleSigningAttempt,
    ) {
        val reason = checkNotNull(attempt.lateReason) { "enqueue called for an attempt that does not queue" }
        val reasonText = checkNotNull(attempt.lateReasonText) { "enqueue called for an attempt that does not queue" }
        tseSigningQueueService.enqueue(
            referenceId = request.referenceId,
            entityType = request.entityType,
            processType = request.kind.processType(),
            processData = request.processData,
            clientId = request.clientId,
            reason = reason,
            reasonText = reasonText,
        )
    }

    override fun signingNoteFor(
        entityType: TseEntityType,
        referenceId: String,
    ): TseSigningNoteDto? = lateSigningRepository.noteFor(entityType, referenceId)

    private fun SaleSigningKind.processType(): String =
        when (this) {
            SaleSigningKind.ORDER -> TseTransactionService.PROCESS_TYPE_ORDER
            SaleSigningKind.RECEIPT -> TseTransactionService.PROCESS_TYPE_RECEIPT
        }
}
