package org.example.project.core.utils

import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding

/**
 * A bill's tender for a Zahlartwechsel (Storno + Neubuchung): payments as (paymentTypeId, amount)
 * and the whole tip. One rule for the till's confirm button and the server's refusal (rule 8).
 */
data class RebookTender(
    val payments: List<Pair<Long, Decimal>>,
    val tip: Decimal,
    /** Redeemed voucher numbers; order does not matter. */
    val vouchers: List<String> = emptyList(),
) {
    /** Every amount positive with at most two decimals, the tip not negative, at least one payment. */
    fun isWellFormed(): Boolean =
        payments.isNotEmpty() &&
            payments.all { (_, amount) -> amount > Decimal.ZERO && amount.hasAtMostTwoDecimals() } &&
            tip >= Decimal.ZERO &&
            tip.hasAtMostTwoDecimals()

    /** The payments cover exactly the bill total plus the tip. */
    fun balances(billTotal: Decimal): Boolean = payments.fold(Decimal.ZERO) { acc, (_, amount) -> acc + amount } == billTotal + tip

    /** The tender (as a multiset, order-free), the voucher numbers or the tip differ from [other]. */
    fun changesFrom(other: RebookTender): Boolean = tip != other.tip || canonical() != other.canonical() || vouchers.sorted() != other.vouchers.sorted()

    /**
     * The tip is below [original]'s. Lowering a tip takes back part of what was booked (an owner tip
     * is taxable revenue), so it needs `BILL_VOID` on top of `BILL_CHANGE_PAYMENT`; keeping or
     * raising it does not.
     */
    fun lowersTipFrom(original: RebookTender): Boolean = tip < original.tip

    private fun canonical(): List<Pair<Long, Decimal>> = payments.sortedWith(compareBy({ it.first }, { it.second }))

    private fun Decimal.hasAtMostTwoDecimals(): Boolean = setScale(2, MoneyRounding.DOWN) == this
}
