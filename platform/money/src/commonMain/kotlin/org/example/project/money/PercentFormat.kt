package org.example.project.money

/** Formatting configuration for [Percentage]. */
data class PercentFormat(
    val separator: DecimalSeparator = DecimalSeparator.DOT,
    val minFractionDigits: Int = 0,
    val maxFractionDigits: Int = 4,
    val rounding: MoneyRounding = MoneyRounding.HALF_UP,
    val suffix: String = " %",
    val forceSign: Boolean = false,
    val minusSign: String = "-",
) {
    companion object {
        val PLAIN: PercentFormat = PercentFormat()

        /** German convention. ASCII minus, for the reason [MoneyFormat.DE_EUR] gives. */
        val DE: PercentFormat =
            PercentFormat(
                separator = DecimalSeparator.COMMA,
            )
    }
}

fun Percentage.format(fmt: PercentFormat = PercentFormat.PLAIN): String {
    val body =
        toPercent().format(
            MoneyFormat(
                separator = fmt.separator,
                groupSeparator = null,
                minFractionDigits = fmt.minFractionDigits,
                maxFractionDigits = fmt.maxFractionDigits,
                rounding = fmt.rounding,
                symbol = null,
                symbolBefore = false,
                forceSign = fmt.forceSign,
                minusSign = fmt.minusSign,
            ),
        )
    return body + fmt.suffix
}
