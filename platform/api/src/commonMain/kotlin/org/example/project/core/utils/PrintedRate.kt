package org.example.project.core.utils

import org.example.project.money.Decimal
import org.example.project.money.PercentFormat
import org.example.project.money.Percentage
import org.example.project.money.format

/**
 * A rate (VAT, discount) as a printed receipt, Z-Bon or PDF shows it to a German reader: the
 * `:platform-money` [PercentFormat.DE] form without trailing zeros — `"19 %"`, `"7 %"`, `"10,7 %"`.
 * The one printed rate form, alongside [printedAmount] for amounts.
 *
 * Display only — DSFinV-K, DATEV and the TSE keep their own rate encodings. A value that is not a
 * decimal is printed as given with the suffix.
 *
 * @param percent Rate in percent units (`"19.00"` = 19 %), as the Z-Bon DTO carries it
 */
fun printedRate(percent: String): String {
    val value = Decimal.parseOrNull(percent.trim()) ?: return "$percent %"
    return Percentage.ofPercent(Decimal.parse(withoutTrailingZeros(value.toPlainString()))).format(PercentFormat.DE)
}

/** [printedRate] for a rate held as a fraction (`0.19` = 19 %), as the bill's VAT detail carries it. */
fun printedRateOfFraction(fraction: String): String {
    val value = Decimal.parseOrNull(fraction.trim()) ?: return "$fraction %"
    return printedRate(Percentage.ofFraction(value).toPercent().toPlainString())
}

/** `"19.00"` → `"19"`, `"10.70"` → `"10.7"`; an integer string is returned unchanged. */
private fun withoutTrailingZeros(plain: String): String = if ('.' in plain) plain.trimEnd('0').trimEnd('.') else plain
