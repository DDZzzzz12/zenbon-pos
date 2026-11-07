/**
 * Maps KassenbuchEntryType to DATEV XML bookingText values.
 *
 * The bookingText field is required in DATEV XML cashLedger elements
 * and describes the nature of the transaction. Maximum 60 characters,
 * with the first 30 characters being the most significant.
 *
 * Mapping considers both the entry type and VAT rate to produce
 * specific German accounting descriptors like "TE19%" (Tageseinnahme 19%).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType

/**
 * Mapper for generating DATEV XML bookingText values from Kassenbuch entry types.
 *
 * Produces concise German accounting terms suitable for DATEV import.
 */
object DatevXmlBookingTextMapper {
    /**
     * Generates a DATEV-compatible bookingText from entry type and VAT rate.
     *
     * For revenue entries (CASH_SALE), appends the VAT rate for clarity.
     * For expense entries (CASH_EXPENSE), includes VAT rate if available.
     * Other entry types use fixed descriptors.
     *
     * @param entryType The Kassenbuch entry type
     * @param vatRate Optional VAT rate string (e.g., "19", "7", "0")
     * @return BookingText string, max 60 characters
     */
    fun getBookingText(
        entryType: KassenbuchEntryType,
        vatRate: String?,
    ): String {
        val normalizedRate = normalizeVatRate(vatRate)

        return when (entryType) {
            KassenbuchEntryType.CASH_SALE -> {
                when (normalizedRate) {
                    "19" -> "TE19%"
                    "7" -> "TE7%"
                    "0" -> "TE0%"
                    else -> if (normalizedRate != null) "TE$normalizedRate%" else "Tageseinnahme"
                }
            }
            KassenbuchEntryType.CASH_EXPENSE -> {
                when (normalizedRate) {
                    "19" -> "Aufwand 19%"
                    "7" -> "Aufwand 7%"
                    "0" -> "Aufwand 0%"
                    else -> if (normalizedRate != null) "Aufwand $normalizedRate%" else "Aufwand"
                }
            }
            KassenbuchEntryType.PRIVATE_WITHDRAWAL -> "Privatentnahme"
            KassenbuchEntryType.CASH_SHORTAGE -> "Zählprotokoll"
            KassenbuchEntryType.CASH_SURPLUS -> "Zählprotokoll"
            KassenbuchEntryType.CASH_TRANSFER_IN -> "Geldtransit"
            KassenbuchEntryType.CASH_TRANSFER_OUT -> "Geldtransit"
            KassenbuchEntryType.OPENING_BALANCE -> "Anfangsbestand"
            KassenbuchEntryType.CLOSING_BALANCE -> "Endbestand"
            KassenbuchEntryType.REFUND -> "Rückerstattung"
            KassenbuchEntryType.CORRECTION_IN -> "Korrektur"
            KassenbuchEntryType.CORRECTION_OUT -> "Korrektur"
            KassenbuchEntryType.CHANGE_FUND -> "Wechselgeld"
            KassenbuchEntryType.CASH_DEPOSIT -> "Bargeldeinlage"
            KassenbuchEntryType.PRIVATE_DEPOSIT -> "Privateinlage"
            KassenbuchEntryType.CASH_WITHDRAWAL -> "Barentnahme"
            KassenbuchEntryType.CASH_PAYMENT -> "Barzahlung"
        }
    }

    /**
     * Normalizes a VAT rate string to a clean integer representation.
     *
     * Handles formats like "19,00", "19.00", "19", "7,00", "7", "0,00" etc.
     *
     * @param vatRate Raw VAT rate string from database
     * @return Normalized rate string (e.g., "19", "7", "0") or null if empty
     */
    private fun normalizeVatRate(vatRate: String?): String? {
        if (vatRate.isNullOrBlank()) return null

        // Replace comma with dot for parsing, then extract integer part
        val cleaned = vatRate.replace(",", ".").trim()
        return try {
            val numericValue = cleaned.toDouble()
            if (numericValue == numericValue.toLong().toDouble()) {
                numericValue.toLong().toString()
            } else {
                cleaned
            }
        } catch (_: NumberFormatException) {
            vatRate.trim()
        }
    }
}
