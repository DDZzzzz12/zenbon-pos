package org.example.project.features.tse.domain

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseSigningStatus

/**
 * Single source of truth for "given the configured TSE mode, the failure behavior,
 * and the outcome of a sign attempt, what signing status should the entity get,
 * should it be queued for Nachsignieren, and should the transaction be rejected?"
 *
 * Pure — no DB, no entities — so it is unit-tested exhaustively without a database.
 */
object TseSigningOutcomeResolver {
    fun resolve(
        mode: TseMode,
        failureBehavior: TseFailureBehavior,
        outcome: TseSignOutcome,
    ): TseSigningDecision =
        when (outcome) {
            TseSignOutcome.Signed ->
                when (mode) {
                    TseMode.SIMULATION -> TseSigningDecision(TseSigningStatus.SIMULATION, enqueue = false, rethrow = null)
                    TseMode.NORMAL, TseMode.DISABLED -> TseSigningDecision(TseSigningStatus.SIGNED, enqueue = false, rethrow = null)
                }
            TseSignOutcome.NoSignature ->
                TseSigningDecision(TseSigningStatus.DISABLED, enqueue = false, rethrow = null)
            is TseSignOutcome.Failed ->
                when (failureBehavior) {
                    TseFailureBehavior.FAIL -> TseSigningDecision(TseSigningStatus.FAILED, enqueue = false, rethrow = outcome.error)
                    TseFailureBehavior.QUEUE -> TseSigningDecision(TseSigningStatus.QUEUED, enqueue = true, rethrow = null)
                }
        }
}
