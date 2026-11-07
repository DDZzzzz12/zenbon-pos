package org.example.project.core.utils

import org.example.project.money.Decimal

/**
 * F59, option A — how much the "Z-Bon erstellen" dialog tops the drawer up from another cash book
 * when the cash tip payouts exceed what it holds. One definition for the till's preview and the
 * server's tests of its Geldtransit rule (`ClosingCashTransfer`).
 *
 * The drawer holds `float + takings` before the payouts (float = the Ladenkasse's book balance,
 * takings = the cash takings no Z-Bon has closed yet).
 *
 * - [shortfall] = `payouts − (float + takings)`: the least the closing needs — below it the
 *   Kassenminus guard refuses the payouts. Null when the drawer covers them.
 * - [floatPreservingTopUp] = `max(payouts − takings, 0)`: what the dialog PREFILLS. The takings pay
 *   what they can, the top-up pays the rest, the float is untouched. The Geldtransit rule then moves
 *   `max(takings + topUp − payouts, 0) = 0` to the safe and the drawer ends exactly at its float.
 *   Always ≥ [shortfall]. The operator may edit it down to the shortfall (drawer ends lower) or up
 *   (the excess travels straight back to the safe in the same closing).
 */
object ClosingTopUpRule {
    fun shortfall(
        cashPayouts: Decimal,
        float: Decimal,
        takings: Decimal,
    ): Decimal? = (cashPayouts - (float + takings)).takeIf { it > Decimal.ZERO }

    fun floatPreservingTopUp(
        cashPayouts: Decimal,
        takings: Decimal,
    ): Decimal = (cashPayouts - takings).takeIf { it > Decimal.ZERO } ?: Decimal.ZERO
}
