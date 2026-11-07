package org.example.project.ui.components

import org.example.project.core.domain.enums.setup.Language
import org.example.project.money.Decimal
import org.example.project.money.MoneyException
import org.example.project.money.MoneyRounding

/**
 * Formats a percentage the server sent in percent units ("19.00", "7", "5.50") for display in
 * [language]: German "19 %", "5,5 %", "46,39 %" (decimal comma, space before the sign — the same
 * shape adminApp's VAT-rate picker renders); English and Chinese "19%", "5.5%", "46.39%".
 *
 * The one percent formatter for every screen — never interpolate `"${value}%"`, which shows the
 * server's raw "46.39" with a dot in a German UI.
 *
 * At most two decimals, trailing zeros dropped. Input that is not a decimal comes back unchanged,
 * like [org.example.project.core.utils.formatCurrencyAmount]: a display must never crash a screen,
 * and the raw value is more useful to the operator than a placeholder.
 */
fun formatPercentForDisplay(
    percent: String,
    language: Language = Language.GERMAN,
): String {
    val value = Decimal.parseOrNull(percent) ?: return percent
    val plain =
        try {
            value.setScale(PERCENT_DISPLAY_SCALE, MoneyRounding.HALF_UP).toPlainString()
        } catch (e: MoneyException) {
            return percent
        }
    val trimmed = if ('.' in plain) plain.trimEnd('0').trimEnd('.') else plain
    val unsigned = if (trimmed == "-0") "0" else trimmed
    return when (language) {
        Language.GERMAN -> "${unsigned.replace('.', ',')} %"
        Language.ENGLISH, Language.CHINESE -> "$unsigned%"
    }
}

/**
 * [formatPercentForDisplay] for a value the server already suffixed — `VatResponseDto.vatPercentage`
 * arrives as `"19.00%"`. Input that is not a number plus `%` comes back unchanged.
 */
fun formatSuffixedPercentForDisplay(
    percent: String,
    language: Language = Language.GERMAN,
): String {
    val bare = percent.trim().removeSuffix("%").trim()
    if (Decimal.parseOrNull(bare) == null) return percent
    return formatPercentForDisplay(bare, language)
}

private const val PERCENT_DISPLAY_SCALE = 2
