package org.example.project.core.utils

/**
 * The amount of a Z-Bon deduction line — "Rabatte", "Stornierungen", "Rückerstattungen",
 * "davon Zahlartwechsel" — as the printed and the PDF Z-Bon show it: the server's positive total
 * ("12.50") behind a minus sign ("-12.50").
 *
 * Zero is printed without the sign: "-0.00 EUR" reads as a deduction that happened (emu16 NOTE-9 /
 * NOTE-11). The amount itself is passed through untouched, so the printed Z-Bon keeps its dot
 * convention. Display only — never feed this into a TSE payload or a DSFinV-K field.
 */
fun zBonDeductionAmount(amount: String): String = if (isZeroAmount(amount)) amount.trimStart('-', '+') else "-$amount"

/** True for "0", "0.00", "-0.00", "0,0" — a decimal string with no non-zero digit. */
private fun isZeroAmount(amount: String): Boolean {
    val unsigned = amount.trim().trimStart('-', '+')
    return unsigned.any { it == '0' } && unsigned.all { it == '0' || it == '.' || it == ',' }
}
