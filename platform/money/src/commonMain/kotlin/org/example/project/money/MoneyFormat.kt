package org.example.project.money

/**
 * Locale-neutral formatting configuration.
 *
 * Deliberately pure common code with no platform locale APIs, so output is deterministic and
 * testable identically on every target.
 */
data class MoneyFormat(
    val separator: DecimalSeparator = DecimalSeparator.DOT,
    val groupSeparator: String? = null,
    val minFractionDigits: Int = 2,
    val maxFractionDigits: Int = 2,
    val rounding: MoneyRounding = MoneyRounding.HALF_UP,
    val symbol: String? = null,
    val symbolBefore: Boolean = false,
    val forceSign: Boolean = false,
    val minusSign: String = "-",
) {
    init {
        if (minFractionDigits < 0 || maxFractionDigits < minFractionDigits) {
            throw DecimalEnvelopeException(
                "invalid fraction digit range $minFractionDigits..$maxFractionDigits",
            )
        }
    }

    companion object {
        val PLAIN: MoneyFormat = MoneyFormat()

        /**
         * German convention: comma decimal separator, dot grouping, trailing euro symbol.
         *
         * The minus is an ASCII hyphen, not U+2212 MINUS SIGN, even though U+2212 is the
         * typographically correct choice and was what this preset used. A consumer of this module
         * renders through a font it chose, and adminApp's is a CJK subset that has no U+2212 —
         * Compose does not fall back by glyph coverage, so the sign drew as *nothing at all* and a
         * −42,30 € debit appeared as "42,30 €". A preset is the thing a caller reaches for without
         * thinking, so it must not carry a character that can silently vanish.
         *
         * [minusSign] is still a parameter: a caller who knows its font covers U+2212 can pass it.
         */
        val DE_EUR: MoneyFormat =
            MoneyFormat(
                separator = DecimalSeparator.COMMA,
                groupSeparator = ".",
                symbol = "€",
            )
    }
}

/** Format this decimal. Values with more fractional digits than allowed are rounded, never cut. */
fun Decimal.format(fmt: MoneyFormat = MoneyFormat.PLAIN): String {
    val targetScale =
        if (scale > fmt.maxFractionDigits) {
            fmt.maxFractionDigits
        } else {
            maxOf(scale, fmt.minFractionDigits)
        }
    val scaled = setScale(targetScale, fmt.rounding)

    val plain = scaled.toPlainString()
    val negative = plain.startsWith('-')
    val unsigned = if (negative) plain.substring(1) else plain

    val dot = unsigned.indexOf('.')
    val intPart = if (dot < 0) unsigned else unsigned.substring(0, dot)
    val fracPart = if (dot < 0) "" else unsigned.substring(dot + 1)

    val groupedInt = fmt.groupSeparator?.let { group(intPart, it) } ?: intPart
    val body = if (fracPart.isEmpty()) groupedInt else "$groupedInt${fmt.separator.char}$fracPart"

    val sign =
        when {
            negative -> fmt.minusSign
            fmt.forceSign -> "+"
            else -> ""
        }

    val symbol = fmt.symbol
    return when {
        symbol == null -> "$sign$body"
        fmt.symbolBefore -> "$sign$symbol$body"
        else -> "$sign$body $symbol"
    }
}

/** Format this money. Falls back to the ISO currency code when [MoneyFormat.symbol] is null. */
fun Money.format(fmt: MoneyFormat = MoneyFormat.PLAIN): String = amount.format(if (fmt.symbol == null) fmt.copy(symbol = currency.code) else fmt)

private fun group(
    digits: String,
    separator: String,
): String {
    if (digits.length <= 3) return digits
    return buildString {
        val firstGroup = digits.length % 3
        if (firstGroup > 0) {
            append(digits, 0, firstGroup)
        }
        var index = firstGroup
        while (index < digits.length) {
            if (isNotEmpty()) append(separator)
            append(digits, index, index + 3)
            index += 3
        }
    }
}
