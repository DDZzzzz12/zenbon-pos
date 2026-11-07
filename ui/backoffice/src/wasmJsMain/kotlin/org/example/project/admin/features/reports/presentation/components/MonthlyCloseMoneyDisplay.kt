package org.example.project.admin.features.reports.presentation.components

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.money.Decimal
import org.example.project.ui.components.formatMoneyForDisplay

/*
 * Display formatting for the Monatsabschluss block. The server sends canonical decimal strings
 * ("258.52"), which the tiles and roll-ups used to print as they came — no € sign, a dot decimal,
 * beside a screen that writes "258,52 €" everywhere else (admin16 Z8d). Every amount now goes
 * through the money kernel's display helper with the response's own currency.
 */

/** The KPI keys whose value is a count, not money — every other key is an amount. */
private val COUNT_KPI_KEYS = setOf("billCount")

/** A KPI tile's value: money formatted German-style with the currency, a count as it came. */
internal fun MonthlyCloseDto.KpiMetricDto.displayValue(currencyCode: String): String =
    if (key in COUNT_KPI_KEYS) value else formatMoneyForDisplay(value, currencyCode)

/**
 * A Kassenbuch entry's DATEV amount ("+84,40" / "-12,00": explicit sign, comma decimal, no
 * grouping) as display money, the sign kept. A string not in that shape is shown unchanged rather
 * than as the money helper's unparseable placeholder.
 */
internal fun datevAmountForDisplay(
    datevAmount: String,
    currencyCode: String,
): String {
    val canonical = datevAmount.trim().removePrefix("+").replace(',', '.')
    val formatted = formatMoneyForDisplay(canonical, currencyCode, forceSign = true)
    return if (formatted.any { it.isDigit() }) formatted else datevAmount
}

/**
 * The arguments of the roll-up's reconciliation line (`MonthlyCloseKeys.revenueReconciliation`):
 * stored Z-Bon gross, Umsatz from the bills, the difference — formatted — and the numbers of the
 * Z-Bons that differ. `null` when the stored closings agree with their bills, which is every period
 * closed since 1.1.0 (admin18 NOTE-21). The server computes every figure; nothing is re-summed here.
 */
internal fun MonthlyCloseDto.ZBonRollupDto.revenueReconciliationArgs(currencyCode: String): List<String>? {
    val difference = Decimal.parseOrNull(totals.storedRevenueDifference) ?: return null
    if (difference.isZero) return null
    val affected =
        rows
            .filter { row -> Decimal.parseOrNull(row.storedRevenueDifference)?.isZero == false }
            .joinToString(", ") { it.zBonNumber }
    return listOf(
        formatMoneyForDisplay(totals.grossRevenue, currencyCode),
        formatMoneyForDisplay(totals.revenueFromBills, currencyCode),
        formatMoneyForDisplay(totals.storedRevenueDifference, currencyCode),
        affected,
    )
}
