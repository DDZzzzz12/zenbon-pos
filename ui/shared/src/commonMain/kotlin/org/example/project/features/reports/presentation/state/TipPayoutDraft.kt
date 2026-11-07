package org.example.project.features.reports.presentation.state

import org.example.project.core.domain.enums.zBon.TipPayoutMethod

/**
 * The tip payout the operator is about to confirm on a Z-Bon's detail.
 *
 * Opened pre-filled with the employee's whole open amount ([remaining]); the operator may lower it
 * for a partial payout. [amount] is the operator's text as typed (comma or dot), sent only once
 * [check] is [TipPayoutAmountCheck.VALID].
 *
 * @property zBonId the closing the tips come from
 * @property employeeId the employee's UUID, as the server named it
 * @property remaining the open amount when the dialog opened (server decimal string)
 * @property isSubmitting true while the request is in flight — the confirm button is disabled so a
 *   double tap cannot book two payouts
 * @property method cash from the drawer, or — F59 — paid via bank/payroll (no Kassenbuch entry)
 */
data class TipPayoutDraft(
    val zBonId: Long,
    val employeeId: String,
    val employeeName: String,
    val remaining: String,
    val amount: String,
    val isSubmitting: Boolean = false,
    val method: TipPayoutMethod = TipPayoutMethod.CASH,
) {
    val check: TipPayoutAmountCheck get() = TipPayoutAmountCheck.of(amount, remaining)
}
