package org.example.project.core.utils

import kotlin.math.abs
import kotlin.math.roundToLong

/** "1234.5" -> "1234.50", "-19.11" -> "-19.11" (two fraction digits, dot separator). */
fun Double.toDecimalString2(): String {
    val negative = this < 0
    val cents = (abs(this) * 100).roundToLong()
    val whole = cents / 100
    val fraction = (cents % 100).toString().padStart(2, '0')
    return "${if (negative) "-" else ""}$whole.$fraction"
}

/** Like [toDecimalString2] but with an explicit "+" for non-negative values. */
fun Double.toSignedDecimalString2(): String = if (this >= 0) "+${toDecimalString2()}" else toDecimalString2()
