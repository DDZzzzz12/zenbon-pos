package org.example.project.core.utils

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Convert decimal to percentage string.
 * Example: 0.19 -> "19"
 */
fun Double.toPercentage(): String = (this * 100).roundToInt().toString()

/**
 * Format a money amount with two fractional digits (e.g. 12.5 -> "12.50").
 * Multiplatform-safe replacement for `"%.2f".format(...)` which is not available in commonMain.
 */
fun Double.toMoneyString(): String {
    val cents = (this * 100).roundToLong()
    val sign = if (cents < 0) "-" else ""
    val absCents = abs(cents)
    val whole = absCents / 100
    val frac = (absCents % 100).toString().padStart(2, '0')
    return "$sign$whole.$frac"
}
