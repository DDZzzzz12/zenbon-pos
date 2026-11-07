package org.example.project.features.dashboard.presentation.state

import org.example.project.money.Decimal

/**
 * The money figures a bill in progress is described by, derived in one place.
 *
 * The payment header used to bind its "Gesamt" to the gross subtotal *before* promotions while
 * deriving "Verbleibend" from the payable amount, so a 30,20 € bill with a 16,50 € free-item
 * promotion read `Bezahlt: 0,00 € · Verbleibend: 13,70 € · Gesamt: 30,20 €` and nothing on the
 * screen stated the 13,70 € the customer actually owed (E2E-2026-09-20 #9). Without a promotion the
 * three agree, so the inconsistency showed up exactly when it mattered.
 *
 * The invariant this type exists to hold: **[paidAmount] + [remainingAmount] == [payableTotal] +
 * [tipAmount]**, at every stage of tendering. Until the customer overpays the tip is zero and it
 * reads "Bezahlt + Verbleibend = Gesamt"; once they do, [remainingAmount] is floored at zero and the
 * overpayment is the tip.
 *
 * "Gesamt" stays the bill. It used to grow by the tip, so 11,00 € tendered on a 10,50 € bill read
 * `Gesamt: 11,00 € · davon Trinkgeld: 0,50 €` — the header then stated the amount paid twice and the
 * bill nowhere, while the receipt printed `SUMME: 10,50` (F65). The tip has its own "davon
 * Trinkgeld" part on that line.
 *
 * @property grossTotal what the items cost before any promotion — still shown on the review panel,
 *   where it is struck through against the reduced figure.
 * @property payableTotal what the customer owes for the bill, after promotions and before any tip;
 *   the only number the payment header may call the total.
 * @property remainingAmount what is still owed; never negative.
 * @property tipAmount an overpayment, which the bill keeps as a tip rather than handing back as change.
 */
data class BillingTotals(
    val grossTotal: Decimal,
    val payableTotal: Decimal,
    val paidAmount: Decimal,
    val remainingAmount: Decimal,
    val tipAmount: Decimal,
) {
    companion object {
        fun of(
            subtotal: Decimal,
            promotionDeduction: Decimal,
            paidAmount: Decimal,
        ): BillingTotals {
            // A promotion worth more than the bill leaves nothing owing; it never turns into a
            // negative balance the customer would appear to be owed.
            val effective = (subtotal - promotionDeduction).coerceAtLeast(Decimal.ZERO)
            val outstanding = effective - paidAmount
            val tip = if (outstanding < Decimal.ZERO) -outstanding else Decimal.ZERO
            return BillingTotals(
                grossTotal = subtotal,
                payableTotal = effective,
                paidAmount = paidAmount,
                remainingAmount = if (tip > Decimal.ZERO) Decimal.ZERO else outstanding,
                tipAmount = tip,
            )
        }
    }
}
