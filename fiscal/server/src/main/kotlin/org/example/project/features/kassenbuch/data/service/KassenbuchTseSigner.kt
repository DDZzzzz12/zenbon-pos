/**
 * The single TSE-signing path for Kassenbuch entries.
 *
 * Extracted from `KassenbuchServiceImpl` once a second caller appeared
 * ([CashBookTransferServiceImpl], which signs the fiscal leg of a Geldtransit). Before this,
 * that caller reached into the concrete `KassenbuchServiceImpl` for one `internal` method; the
 * alternative — duplicating the FAIL/QUEUE/PENDING-strand decision — is worse, because two copies
 * of KassenSichV-audited logic drifting apart is exactly the failure this codebase guards against
 * elsewhere (see the round-8 R7-D9 comments).
 */
package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.tse.domain.TseSignOutcome
import org.example.project.features.tse.domain.TseSigningOutcomeResolver
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.tse.domain.toLateSigningCause
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Signs a Kassenbuch entry with the TSE and records the outcome on the header.
 *
 * Owns only the three collaborators the signing decision needs, so neither service has to expose
 * internals to the other.
 */
class KassenbuchTseSigner(
    private val configManager: IShopConfigurationManager,
    private val tseServiceFactory: TseServiceFactory,
    private val tseSigningQueueService: TseSigningQueueService,
) {
    private val logger = LoggerFactory.getLogger(KassenbuchTseSigner::class.java)

    /**
     * Phase 2 of the create-then-sign flow — **deliberately outside the creating transaction**, so
     * a rollback cannot orphan a TSE record.
     *
     * Only ever called for an entry in a FISCAL cash book. A non-fiscal book (Tresor, Nebenkasse)
     * is not an elektronisches Aufzeichnungssystem, so §146a AO does not reach it; those entries
     * are stamped [TseSigningStatus.NOT_APPLICABLE] at creation and never arrive here.
     * `TseSigningQueueService.enqueue` independently rejects them as a backstop.
     *
     * The mode/behaviour/outcome triple is resolved by [TseSigningOutcomeResolver] rather than
     * branched on here, so the FAIL-vs-QUEUE policy lives in one pure, exhaustively tested place.
     */
    suspend fun sign(
        headerId: Long,
        belegNr: String,
        processData: String,
        processType: String,
        clientId: String,
    ) {
        val mode = configManager.getTseMode()
        val failureBehavior = configManager.getTseConfig().failureBehavior
        var signedTx: TseTransaction? = null
        val outcome =
            try {
                val tseTx =
                    tseServiceFactory.getTseService().signTransaction(
                        processType = processType,
                        processData = processData,
                        referenceId = headerId.toString(),
                        clientId = clientId,
                        entityType = TseEntityType.KASSENBUCH,
                    )
                signedTx = tseTx
                if (tseTx == null) TseSignOutcome.NoSignature else TseSignOutcome.Signed
            } catch (e: PosException) {
                TseSignOutcome.Failed(e)
            }

        val decision = TseSigningOutcomeResolver.resolve(mode, failureBehavior, outcome)

        dbQuery {
            val header = KassenbuchHeader.findById(headerId)!!
            if (signedTx != null) header.tseTransaction = signedTx
            header.tseSigningStatus = decision.status
        }

        if (decision.enqueue) {
            val (lateReason, lateReasonText) = outcome.toLateSigningCause()
            try {
                tseSigningQueueService.enqueue(
                    referenceId = headerId.toString(),
                    entityType = TseEntityType.KASSENBUCH,
                    processType = processType,
                    processData = processData,
                    clientId = clientId,
                    reason = lateReason,
                    reasonText = lateReasonText,
                )
                logger.info("KassenbuchTseSigner.sign: KB-{} queued for Nachsignieren", belegNr)
            } catch (eq: PosException) {
                logger.error("KassenbuchTseSigner.sign: failed to queue KB-{}: code={} msg={}", belegNr, eq.code, eq.message, eq)
                // Back to PENDING so TsePendingSweeper rescues it: an entry that failed BOTH the
                // sign and the enqueue must not be left claiming a terminal state.
                dbQuery { KassenbuchHeader.findById(headerId)!!.tseSigningStatus = TseSigningStatus.PENDING }
            }
        }

        decision.rethrow?.let {
            logger.error("KassenbuchTseSigner.sign: TSE signing failed for KB-{}, FAIL mode", belegNr, it)
            throw PosException(
                PosErrorCode.TSE_SIGNING_FAILED,
                HttpStatusCode.ServiceUnavailable,
                "TSE signing failed for Kassenbuch entry KB-$belegNr",
            )
        }
    }
}
