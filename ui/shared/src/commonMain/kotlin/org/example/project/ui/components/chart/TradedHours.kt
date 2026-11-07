package org.example.project.ui.components.chart

import org.example.project.money.Decimal

/**
 * The inclusive hour range [first traded hour .. last traded hour] for an `hour → amount` map, or
 * null when nothing traded. A traded hour is one whose amount parses to a strictly positive value.
 * Only the ENDS are trimmed — interior zero hours stay inside the range so the time axis remains
 * continuous and an afternoon lull reads as a genuine gap, not as compressed-away time.
 */
fun tradedHourRange(hourlyRevenue: Map<Int, String>): IntRange? {
    val traded =
        hourlyRevenue.entries
            .filter { (_, amount) -> (Decimal.parseOrNull(amount) ?: Decimal.ZERO) > Decimal.ZERO }
            .map { it.key }
    if (traded.isEmpty()) return null
    return traded.min()..traded.max()
}
