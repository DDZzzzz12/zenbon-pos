/**
 * Utility functions for formatting decimal numbers with consistent precision.
 *
 * Provides functions to format Double and BigDecimal values to string representation
 * with exactly 2 decimal places, handling rounding and trailing zeros appropriately.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.formatter

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formats a Double to a string with exactly 2 decimal places.
 *
 * Converts values like 3.4000 to "3.40" and 13.4255 to "13.43".
 * Uses HALF_UP rounding mode for consistent financial calculations.
 *
 * @param value Double value to format
 * @return Formatted string with exactly 2 decimal places
 *
 * @example
 * ```
 * formatDecimal(3.4000) // returns "3.40"
 * formatDecimal(13.4255) // returns "13.43"
 * formatDecimal(0.0) // returns "0.00"
 * formatDecimal(99.999) // returns "100.00"
 * ```
 *
 * The decimal separator is forced to "." via [Locale.ROOT] symbols so the output is
 * locale-independent. Without this, a German JVM default locale ([Locale.GERMANY]) would
 * emit a comma (e.g. "13,43"), corrupting any fiscal/exported/TSE-signed string this value
 * feeds into (DSFinV-K CSVs, DATEV exports, TSE ProcessData, receipts, QR codes).
 */
fun formatDecimal(value: Double): String {
    val formatter = DecimalFormat("#0.00", DecimalFormatSymbols(Locale.ROOT))
    formatter.roundingMode = RoundingMode.HALF_UP
    return formatter.format(value)
}

/**
 * Formats a BigDecimal to a string with exactly 2 decimal places.
 *
 * Converts values like BigDecimal("3.4000") to "3.40" and
 * BigDecimal("13.4255") to "13.43". Uses HALF_UP rounding mode
 * for consistent financial calculations.
 *
 * @param value BigDecimal value to format
 * @return Formatted string with exactly 2 decimal places
 *
 * @example
 * ```
 * formatDecimal(BigDecimal("3.4000")) // returns "3.40"
 * formatDecimal(BigDecimal("13.4255")) // returns "13.43"
 * formatDecimal(BigDecimal.ZERO) // returns "0.00"
 * formatDecimal(BigDecimal("99.999")) // returns "100.00"
 * ```
 */
fun formatDecimal(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toString()

/**
 * Extension function for Double to format with 2 decimal places.
 *
 * Provides a convenient way to call formatDecimal as an extension.
 *
 * @receiver Double value to format
 * @return Formatted string with exactly 2 decimal places
 *
 * @example
 * ```
 * val price = 13.4255
 * val formatted = price.toDecimalString() // returns "13.43"
 * ```
 */
fun Double.toDecimalString(): String = formatDecimal(this)

/**
 * Extension function for BigDecimal to format with 2 decimal places.
 *
 * Provides a convenient way to call formatDecimal as an extension.
 *
 * @receiver BigDecimal value to format
 * @return Formatted string with exactly 2 decimal places
 *
 * @example
 * ```
 * val price = BigDecimal("13.4255")
 * val formatted = price.toDecimalString() // returns "13.43"
 * ```
 */
fun BigDecimal.toDecimalString(): String = formatDecimal(this)

/**
 * The value as a plain decimal string with its scale untouched — for columns that legitimately hold
 * more than two decimals (EN 16931 unit prices, quantities, exchange rates).
 *
 * Use [toDecimalString] instead for amounts a human reads as money; it pins scale 2.
 */
fun BigDecimal.toExactDecimalString(): String = this.toPlainString()

/**
 * A money amount for a human-readable message: two decimals (`23.80`, not the stored `23.8000`), unless
 * the value really carries sub-cent digits, which are then shown exactly (`23.8049`).
 *
 * For messages that report a MISMATCH between two amounts. [toDecimalString] rounds, so a difference
 * below one cent would print two identical figures and leave the reader unable to see the problem.
 */
fun BigDecimal.toMoneyMessageString(): String = if (stripTrailingZeros().scale() <= 2) toDecimalString() else stripTrailingZeros().toPlainString()

/**
 * Converts a Double to BigDecimal with proper precision handling.
 *
 * Creates a BigDecimal from the string representation of the Double
 * to avoid floating-point precision issues. Sets scale to 2 decimal
 * places with HALF_UP rounding for financial calculations.
 *
 * @param value Double value to convert
 * @return BigDecimal with 2 decimal places precision
 *
 * @example
 * ```
 * toBigDecimal(3.4000) // returns BigDecimal("3.40")
 * toBigDecimal(13.4255) // returns BigDecimal("13.43")
 * toBigDecimal(99.999) // returns BigDecimal("100.00")
 * ```
 */
fun toBigDecimal(value: Double): BigDecimal = BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP)

/**
 * Converts a String to BigDecimal with proper precision handling.
 *
 * Parses the string as a BigDecimal and sets scale to 2 decimal
 * places with HALF_UP rounding for financial calculations.
 * Throws NumberFormatException if string is not a valid number.
 *
 * @param value String representation of a decimal number
 * @return BigDecimal with 2 decimal places precision
 * @throws NumberFormatException if the string is not a valid decimal number
 *
 * @example
 * ```
 * toBigDecimal("3.4000") // returns BigDecimal("3.40")
 * toBigDecimal("13.4255") // returns BigDecimal("13.43")
 * toBigDecimal("99.999") // returns BigDecimal("100.00")
 * ```
 */
fun toBigDecimal(value: String): BigDecimal = BigDecimal(value).setScale(2, RoundingMode.HALF_UP)

fun BigDecimal.toPercentage(): String = "${this.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)}"

/**
 * Safely converts a String to BigDecimal with proper precision handling.
 *
 * Attempts to parse the string as a BigDecimal and sets scale to 2 decimal
 * places with HALF_UP rounding. Returns null if the string cannot be parsed.
 *
 * @param value String representation of a decimal number
 * @return BigDecimal with 2 decimal places precision, or null if parsing fails
 *
 * @example
 * ```
 * toBigDecimalOrNull("3.4000") // returns BigDecimal("3.40")
 * toBigDecimalOrNull("invalid") // returns null
 * toBigDecimalOrNull("") // returns null
 * ```
 */
fun toBigDecimalOrNull(value: String): BigDecimal? =
    try {
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP)
    } catch (e: NumberFormatException) {
        null
    }

/**
 * Converts a BigDecimal to Double with proper 2-decimal formatting.
 *
 * First formats the BigDecimal to exactly 2 decimal places using HALF_UP rounding,
 * then converts to Double. This ensures consistent precision and avoids floating
 * point precision issues.
 *
 * @param value BigDecimal value to convert
 * @return Double with 2 decimal places precision
 *
 * @example
 * ```
 * toFormattedDouble(BigDecimal("3.4000")) // returns 3.40
 * toFormattedDouble(BigDecimal("13.4255")) // returns 13.43
 * toFormattedDouble(BigDecimal("99.999")) // returns 100.00
 * ```
 */
fun toFormattedDouble(value: BigDecimal): Double = value.setScale(2, RoundingMode.HALF_UP).toDouble()

fun List<BigDecimal>.average(
    scale: Int = 2,
    roundingMode: RoundingMode = RoundingMode.HALF_UP,
): BigDecimal {
    if (this.isEmpty()) return BigDecimal.ZERO
    val sum = this.reduce { acc, bd -> acc.add(bd) }
    return sum.divide(BigDecimal(this.size), scale, roundingMode)
}

fun parseBigDecimal(amountString: String): BigDecimal? {
    // Remove leading + sign if present (keep - for negative numbers)
    // NumberFormat doesn't handle explicit + prefix
    val cleanedString =
        amountString
            .trimStart()
            .let {
                if (it.startsWith("+")) it.substring(1) else it
            }.replace(",", ".")

    val decimal = toBigDecimalOrNull(cleanedString)

    return decimal
}

/**
 * Formats a BigDecimal to a string with German number format.
 *
 * Uses comma as decimal separator and dot as thousands separator
 * according to German/European number formatting standards.
 * Sets scale to 2 decimal places with HALF_UP rounding.
 *
 * @param value BigDecimal value to format
 * @return Formatted string with German number format (e.g., "1.234,56")
 *
 * @example
 * ```
 * formatGerman(BigDecimal("1234.56")) // returns "1.234,56"
 * formatGerman(BigDecimal("99.9")) // returns "99,90"
 * formatGerman(BigDecimal("0")) // returns "0,00"
 * ```
 */

/**
 * Formats a BigDecimal to DATEV Kassenbuch CSV VorzBetrag format.
 *
 * Output: signed amount with comma decimal separator.
 * Examples: 100.50 -> "+100,50", -75.25 -> "-75,25"
 */
fun formatVorzBetragForDatev(amount: BigDecimal): String {
    val scaled = amount.setScale(2, RoundingMode.HALF_UP)
    val sign = if (scaled >= BigDecimal.ZERO) "+" else ""
    return "$sign${scaled.toPlainString().replace(".", ",")}"
}

fun formatGerman(value: BigDecimal): String {
    val formatter = DecimalFormat("#,##0.00")
    formatter.roundingMode = RoundingMode.HALF_UP
    // German locale uses comma as decimal separator and dot as thousands separator
    val symbols = formatter.decimalFormatSymbols
    symbols.decimalSeparator = ','
    symbols.groupingSeparator = '.'
    formatter.decimalFormatSymbols = symbols
    return formatter.format(value.setScale(2, RoundingMode.HALF_UP))
}
