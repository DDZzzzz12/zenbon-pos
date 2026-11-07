package org.example.project.features.reports.presentation.state

import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.money.Decimal
import org.example.project.money.format

/**
 * One employee's row in the "Z-Bon erstellen" dialog's tip-payout section (F58): their employee tips
 * of the period being closed, and what the operator will hand them in this closing.
 *
 * Checked and prefilled with [total] when the dialog opens. [amount] is the operator's text as typed
 * (comma or dot); a checked row is paid only once [check] is [TipPayoutAmountCheck.VALID] — the same
 * rule the payout after the closing applies, capped at [total]. The server re-checks every rule.
 *
 * @property total the employee's employee tips of the open period (server decimal string)
 * @property cash the part tendered in cash — display only
 * @property card the part tendered by card — display only; the drawer advances it
 * @property method cash from the drawer, or — F59 — "paid via bank/payroll": no cash moves, the
 *   payout only settles the open amount, so it leaves the drawer figures alone
 */
data class ClosingTipPayoutRow(
    val employeeId: String,
    val employeeName: String,
    val total: String,
    val cash: String,
    val card: String,
    val amount: String,
    val checked: Boolean = true,
    val method: TipPayoutMethod = TipPayoutMethod.CASH,
) {
    val check: TipPayoutAmountCheck get() = TipPayoutAmountCheck.of(amount, total)

    /** What this row pays out: the typed amount when checked and valid, else nothing. */
    val payout: Decimal?
        get() = if (checked && check == TipPayoutAmountCheck.VALID) amount.toUserInputDecimalOrNull() else null

    /** What this row hands out of the drawer: [payout] when paid in cash, else nothing. */
    val cashPayout: Decimal? get() = payout?.takeIf { method == TipPayoutMethod.CASH }

    /**
     * A checked row whose amount the server would refuse — blocks the closing. That includes an
     * empty field and an explicit "0" (F65): a ticked box that pays nothing is a contradiction, and
     * reading it as "unticked" would let the closing go through on a figure the operator was still
     * typing. They untick the row or enter an amount; [missingAmount] tells the field which hint to show.
     */
    val blocksClosing: Boolean get() = checked && check != TipPayoutAmountCheck.VALID

    /**
     * The operator changed this row from how the dialog opened it: unchecked, switched away from
     * cash, or typed an amount other than the prefilled whole tip.
     */
    val isEdited: Boolean
        get() = !checked || method != TipPayoutMethod.CASH || amount != Decimal.parseOrNull(total)?.format(TIP_PAYOUT_PREFILL)

    /** A checked row with nothing to pay: the field is empty or says zero. */
    val missingAmount: Boolean get() = checked && isMissingAmount(amount)

    companion object {
        /** [text] is blank or a zero amount ("0", "0,00") — the hint asks for an amount or an untick. */
        fun isMissingAmount(text: String): Boolean = text.isBlank() || text.toUserInputDecimalOrNull()?.compareTo(Decimal.ZERO) == 0
    }
}
