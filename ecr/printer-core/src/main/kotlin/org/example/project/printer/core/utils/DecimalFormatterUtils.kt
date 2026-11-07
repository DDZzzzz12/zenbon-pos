/**
 * Decimal formatting utilities for the printer module.
 *
 * Provides percentage and money formatting used in receipt printing
 * (e.g., VAT rate display, EUR amounts).
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.utils

import org.example.project.core.utils.printedAmount
import org.example.project.printer.core.enums.CodePage
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Converts a BigDecimal fractional value to a percentage string.
 *
 * Multiplies by 100 and rounds to whole number using HALF_UP.
 * Example: BigDecimal("0.19") → "19"
 *
 * @receiver BigDecimal fractional value (e.g., 0.19 for 19%)
 * @return Percentage string without the % symbol
 */
fun BigDecimal.toPercentage(): String = "${this.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)}"

/**
 * The single place a printed ESC/POS document formats an amount: German form (`"11,50"`,
 * `"1.234,50"`), via the shared [printedAmount] and so the `:platform-money` German formatter.
 *
 * Every amount on a receipt, Z-Bon or reference print goes through here — never `String.format`
 * (JVM-locale dependent) and never the raw wire string (dot form). The TSE block is NOT an amount
 * and is printed exactly as the TSE returned it.
 *
 * @param amount Wire decimal string (`"11.50"`, `"-6.0000"`)
 * @return Amount without currency, e.g. `"11,50"`
 */
fun printAmount(amount: String): String = printedAmount(amount)

/** [printAmount] for an amount summed in the formatter. */
fun printAmount(amount: BigDecimal): String = printedAmount(amount.toPlainString())

/**
 * An amount with the currency suffix the [codePage] can render: `"11,50 EUR"` on CP850 (which has
 * no `€`), `"11,50 €"` only on a page that encodes it.
 */
fun printMoney(
    amount: String,
    codePage: CodePage = CodePage.CP850,
): String = "${printAmount(amount)} ${codePage.currencySuffix}"

/** [printMoney] for an amount summed in the formatter. */
fun printMoney(
    amount: BigDecimal,
    codePage: CodePage = CodePage.CP850,
): String = "${printAmount(amount)} ${codePage.currencySuffix}"
