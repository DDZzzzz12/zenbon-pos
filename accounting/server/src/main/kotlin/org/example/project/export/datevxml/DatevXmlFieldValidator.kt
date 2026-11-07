/**
 * Central validation and sanitization utilities for DATEV XML field constraints.
 *
 * Enforces XSD max-length constraints, validates amounts, and sanitizes invoice IDs
 * to prevent DATEV import rejection. All constants are derived from the DATEV XSD
 * type definitions (Document_v060.xsd, Belegverwaltung_online_ledger_import_v060.xsd).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml

import org.slf4j.Logger
import java.math.BigDecimal

/**
 * Validator for DATEV XML field constraints based on XSD type definitions.
 *
 * Provides truncation with logging, amount validation, and invoice ID sanitization
 * to ensure generated XML conforms to the DATEV schema.
 */
object DatevXmlFieldValidator {
    // ============================================
    // XSD Max-Length Constants
    // ============================================

    /** Max length for clientName in document.xml header (XSD type p10023) */
    const val MAX_CLIENT_NAME = 36

    /** Max length for generator_info in LedgerImport (XSD type p10040) */
    const val MAX_GENERATOR_INFO = 40

    /** Max length for invoiceId / belegNr (XSD type p10040, maxLength=36) */
    const val MAX_INVOICE_ID = 36

    /** Max length for information / Belegtext (XSD type p30, maxLength=120) */
    const val MAX_INFORMATION = 120

    /** Max length for bookingText / Buchungstext (XSD type p10060) */
    const val MAX_BOOKING_TEXT = 60

    /** Max length for supplierName / Lieferantenname (XSD type p10050) */
    const val MAX_SUPPLIER_NAME = 50

    /** Max length for bpAccountNo / Kreditorenkonto (XSD type p10009) */
    const val MAX_BP_ACCOUNT_NO = 9

    /** Max length for costCategoryId / Kost1/Kost2 (XSD type p10041, maxLength=36) */
    const val MAX_COST_CATEGORY_ID = 36

    /** Max length for buCode / BU-Schlüssel (XSD type p10033, 0-9999) */
    const val MAX_BU_CODE = 4

    /** Max length for partyId / Geschäftspartner-ID (XSD type p10011, maxLength=15) */
    const val MAX_PARTY_ID = 15

    /** Max length for vatId / USt-IdNr (XSD type p10027, maxLength=15) */
    const val MAX_VAT_ID = 15

    /** Max length for supplierCity / Lieferantenstadt (XSD type p10019, maxLength=30) */
    const val MAX_SUPPLIER_CITY = 30

    /** Max length for IBAN (ISO 13616 standard, max 34 characters) */
    const val MAX_IBAN = 34

    /** Max length for consultantNumber / Beraternummer (XSD type p10049, 1000-9999999) */
    const val MAX_CONSULTANT_NUMBER = 7

    /** Max length for clientNumber / Mandantennummer (XSD type p10050, 0-99999) */
    const val MAX_CLIENT_NUMBER = 5

    // ============================================
    // Truncation
    // ============================================

    /**
     * Truncates a string to the given max length, logging a warning if truncation occurs.
     *
     * Returns the original string unchanged if it fits within the limit,
     * or null if the input is null.
     *
     * @param value The string value to truncate (nullable)
     * @param maxLength Maximum allowed length from XSD
     * @param fieldName Human-readable field name for log messages
     * @param logger SLF4J logger for truncation warnings
     * @return Truncated string, or null if input was null
     */
    fun truncate(
        value: String?,
        maxLength: Int,
        fieldName: String,
        logger: Logger,
    ): String? {
        if (value == null) return null
        return if (value.length > maxLength) {
            logger.warn(
                "DATEV field '{}' truncated from {} to {} chars: '{}'",
                fieldName,
                value.length,
                maxLength,
                value.take(maxLength),
            )
            value.take(maxLength)
        } else {
            value
        }
    }

    // ============================================
    // Amount Validation
    // ============================================

    /**
     * Parses a decimal string to BigDecimal, throwing on invalid or zero amounts.
     *
     * DATEV rejects 0.00 amounts and invalid numbers would corrupt the
     * consolidatedAmount sum. This method replaces the silent fallback
     * to BigDecimal.ZERO that previously hid data corruption.
     *
     * @param value Decimal string with dot or comma separator (e.g., "123.45", "123,45", "-89.50")
     * @param context Description of what's being parsed for error messages
     * @return Parsed BigDecimal value (guaranteed non-zero)
     * @throws IllegalArgumentException if value is unparseable or zero
     */
    fun requireValidAmount(
        value: String,
        context: String,
    ): BigDecimal {
        val cleaned = value.replace(",", ".").trim()
        val amount =
            try {
                BigDecimal(cleaned)
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Unparseable amount '$value' in $context: ${e.message}")
            }
        require(amount.compareTo(BigDecimal.ZERO) != 0) {
            "Zero amount '$value' in $context — DATEV rejects 0.00 entries"
        }
        return amount
    }

    // ============================================
    // Optional Field Handling
    // ============================================

    /**
     * The `clientName` every DATEV XML export (Kassenbuch, AP, AR) writes into its document.xml header:
     * the shop's configured name, cut to the XSD's [MAX_CLIENT_NAME]. One definition, so the exports
     * cannot drift apart again — the AR export once wrote the Kassen-Name ("Kasse") instead.
     */
    fun clientName(
        shopName: String,
        logger: Logger,
    ): String = truncate(shopName, MAX_CLIENT_NAME, "clientName", logger) ?: shopName

    /**
     * Truncates a string to the given max length, returning null for blank values.
     *
     * Use this for optional XML fields with XSD `minLength=1` constraints
     * (e.g., bookingText type p10036, information type p30). Empty strings would
     * violate minLength and cause DATEV import rejection.
     *
     * @param value The string value to truncate (nullable)
     * @param maxLength Maximum allowed length from XSD
     * @param fieldName Human-readable field name for log messages
     * @param logger SLF4J logger for truncation warnings
     * @return Truncated string, or null if input was null/blank
     */
    fun truncateOrNull(
        value: String?,
        maxLength: Int,
        fieldName: String,
        logger: Logger,
    ): String? {
        if (value.isNullOrBlank()) return null
        return truncate(value, maxLength, fieldName, logger)
    }

    // ============================================
    // Invoice ID Sanitization
    // ============================================

    /** XSD p10040 allowed character pattern — matches characters NOT in the allowed set */
    private val INVOICE_ID_INVALID_CHARS = Regex("[^a-zA-Z0-9\$%&*+\\-/]")

    /**
     * Strips characters not allowed by XSD type p10040 pattern `[a-zA-Z0-9$%&*+\-/]`.
     *
     * Characters like dots, spaces, underscores, umlauts, and parentheses are removed.
     * Logs a warning if any characters were stripped.
     *
     * @param value Raw invoice ID string
     * @param context Description for log messages
     * @param logger SLF4J logger for warnings
     * @return String with only allowed characters
     */
    private fun stripInvalidInvoiceIdChars(
        value: String,
        context: String,
        logger: Logger,
    ): String {
        val cleaned = INVOICE_ID_INVALID_CHARS.replace(value, "")
        if (cleaned != value) {
            logger.warn("DATEV invoiceId ($context) stripped invalid chars: '{}' → '{}'", value, cleaned)
        }
        return cleaned
    }

    /**
     * Sanitizes an invoice ID for DATEV XML export.
     *
     * Returns null for blank or "0" values (belegNr=0 should not produce an invoiceId).
     * Strips characters not allowed by XSD type p10040, then truncates to [MAX_INVOICE_ID].
     *
     * @param invoiceId Raw invoice ID string (nullable)
     * @param context Description for log messages (e.g., "header 42")
     * @param logger SLF4J logger for warnings
     * @return Sanitized invoice ID, or null if blank/zero/all-invalid
     */
    fun sanitizeInvoiceId(
        invoiceId: String?,
        context: String,
        logger: Logger,
    ): String? {
        if (invoiceId.isNullOrBlank() || invoiceId == "0") return null
        val stripped = stripInvalidInvoiceIdChars(invoiceId, context, logger)
        if (stripped.isBlank()) return null
        return truncate(stripped, MAX_INVOICE_ID, "invoiceId ($context)", logger)
    }
}
