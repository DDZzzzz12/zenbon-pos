package org.example.project.features.reports.presentation.viewmodel

import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding
import org.example.project.ui.components.formatMoneyForDisplay

private const val AVERAGE_SCALE = 2

/**
 * Average tip per bill, formatted for display. [totalTips] is the server's bare decimal string
 * (not the already-formatted display value). Zero bills or an unparseable total read as zero.
 */
fun averageTipPerBill(
    totalTips: String,
    billCount: Int,
    currency: String,
): String {
    val total = Decimal.parseOrNull(totalTips)
    if (total == null || billCount <= 0) return formatMoneyForDisplay("0", currency)
    val average = total.divide(Decimal.of(billCount), AVERAGE_SCALE, MoneyRounding.HALF_UP)
    return formatMoneyForDisplay(average.toPlainString(), currency)
}
