package org.example.project.core.utils

import org.example.project.money.Decimal
import org.example.project.money.MoneyException
import org.example.project.money.MoneyRounding
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * [total] ÷ [count], 2 dp, HALF_UP, formatted for display — the "per bill" figure of a period. Same
 * policy as the server's `PeriodComparison.previousAverageBill`, so a current-period figure and its
 * baseline are derived identically. Never a Double. An empty period yields a formatted zero; a
 * malformed total degrades to [formatMoneyForDisplay]'s placeholder rather than throwing.
 */
fun averageMoneyPerCount(
    total: String,
    count: Int,
    currency: String,
): String {
    if (count <= 0) return formatMoneyForDisplay("0.00", currency)
    return try {
        val average =
            Decimal
                .parse(total)
                .divide(Decimal.of(count), scale = 2, rounding = MoneyRounding.HALF_UP)
        formatMoneyForDisplay(average.toPlainString(), currency)
    } catch (e: MoneyException) {
        formatMoneyForDisplay(total, currency)
    }
}
