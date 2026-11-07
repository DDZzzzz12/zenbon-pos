package org.example.project.core.utils.datev

/**
 * Normalizes VAT-rate strings to a canonical form: comma decimal separators
 * become dots, trailing zeros are dropped ("19,00" -> "19", "10,70" -> "10.7").
 *
 * Used at entry validation AND at DATEV account-mapping lookups so stored
 * historical variants ("19,00") and user input ("19.0") hit the same cell.
 *
 * Pure Kotlin (commonMain) — no java.math.BigDecimal.
 */
object VatRateNormalizer {
    /** @return canonical rate string, or null if [raw] is blank/non-numeric/negative. */
    fun normalize(raw: String?): String? {
        val cleaned = raw?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() } ?: return null
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite() || value < 0.0) return null
        val whole = value.toLong()
        return if (value == whole.toDouble()) whole.toString() else value.toString()
    }
}
