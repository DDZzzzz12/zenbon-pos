package org.example.project.core.utils

import org.example.project.core.domain.enums.setup.Language
import org.example.project.money.Decimal
import kotlin.math.abs

/**
 * A server hours string (`"30.00"`, `"27.5"`) as whole minutes, rounded half up; null when it is not
 * a decimal. Exact `:platform-money` arithmetic, so no `Double` is involved: `"37.25"` is `2235`.
 */
fun String.hoursToMinutesOrNull(): Int? {
    val hours = Decimal.parseOrNull(trim()) ?: return null
    return (hours * Decimal.of(60)).setScale(0).toPlainString().toIntOrNull()
}

/**
 * [totalMinutes] as hours with at most two decimals: `1650` is `27,5 Std.` (German), `27.5 h`
 * (English), `27.5 小时` (Chinese). Whole hours drop the fraction.
 */
fun formatHours(
    totalMinutes: Int,
    language: Language,
): String {
    val sign = if (totalMinutes < 0) "-" else ""
    val minutes = abs(totalMinutes)
    val whole = minutes / 60
    val rest = minutes % 60
    // Rounded, not truncated: 40 min is 0.666... h and must read 0,67, not 0,66.
    val fraction = if (rest == 0) "" else ((rest * 100 + 30) / 60).toString().padStart(2, '0').trimEnd('0')
    val separator = if (language == Language.GERMAN) "," else "."
    val number = if (fraction.isEmpty()) "$sign$whole" else "$sign$whole$separator$fraction"
    return when (language) {
        Language.GERMAN -> "$number Std."
        Language.ENGLISH -> "$number h"
        Language.CHINESE -> "$number 小时"
    }
}
