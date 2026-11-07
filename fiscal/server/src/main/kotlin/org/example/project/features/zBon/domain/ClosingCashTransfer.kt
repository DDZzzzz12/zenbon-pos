package org.example.project.features.zBon.domain

import java.math.BigDecimal

/**
 * THE rule for how much cash the closing's daily Geldtransit moves to the safe.
 *
 * ```
 * transfer = min( max(cashTakings + topUp − cashTipPayouts, 0), max(drawerBalance, 0) )
 * ```
 *
 * - **cashTakings** — the closing's revenue bucket of the fiscal book (unchanged rule).
 * - **cashTipPayouts** — the employee tips paid out in cash INSIDE this closing (F58): that cash left
 *   the drawer with the staff, so it cannot also go to the safe. A bank/payroll settlement (F59) moves
 *   no cash and is not part of it. A payout booked AFTER a closing is an ordinary withdrawal of the
 *   following open period and does not reach this rule.
 * - **topUp** — the cash put into the drawer from another book inside this closing to cover those
 *   payouts (F59, option A). It is a Geldtransit IN, bucketed NEUTRAL, so it is not in `cashTakings`
 *   and must be added here explicitly.
 * - **drawerBalance** — the fiscal book's balance at the moment the transfer is booked (after the
 *   aggregate, top-up, payouts and Kassendifferenz). The Kassenminus guard (F59) refuses any transfer
 *   above it; capping here means a count shortage larger than the float shrinks the transfer instead
 *   of refusing the whole closing. The transfer moves what is there.
 *
 * **Why the drawer still ends at its float (F, the balance before the period).** Before the transfer
 * the drawer holds `F + takings + topUp − payouts (+ difference)`. Taking away
 * `takings + topUp − payouts` leaves exactly F whenever that term is ≥ 0. When the payouts exceed the
 * takings, the float advanced the rest: the term is negative, nothing is transferred, and the drawer
 * ends at `F + takings + topUp − payouts` — below F by what it advanced, never below 0, since the
 * guard admitted the payouts only if `F + takings + topUp ≥ payouts`. So:
 *
 * - the dialog's prefill, `payouts − takings` (`ClosingTopUpRule.floatPreservingTopUp`, shared with
 *   the till), keeps the float exactly and transfers nothing;
 * - the bare shortfall `payouts − (F + takings)` — the least the guard accepts — leaves the drawer at 0;
 * - anything above that travels straight back to the safe in this same closing, so a top-up can
 *   never leave the drawer above its float.
 *
 * Without a top-up the rule is exactly the F58 rule, `max(takings − payouts, 0)`. A result of zero
 * books no transfer at all.
 */
object ClosingCashTransfer {
    /** What the closing hands the rule. */
    data class Inputs(
        val cashTakings: BigDecimal,
        val cashTipPayouts: BigDecimal,
        val topUp: BigDecimal = BigDecimal.ZERO,
        val drawerBalance: BigDecimal,
    )

    fun amount(inputs: Inputs): BigDecimal {
        require(inputs.cashTipPayouts.signum() >= 0) { "in-closing tip payouts must not be negative" }
        require(inputs.topUp.signum() >= 0) { "the top-up must not be negative" }
        val net = (inputs.cashTakings + inputs.topUp - inputs.cashTipPayouts).max(BigDecimal.ZERO)
        return net.min(inputs.drawerBalance.max(BigDecimal.ZERO))
    }
}
