/**
 * Utility functions for DSFinV-K CSV generation.
 *
 * Provides common formatting and escaping functions used by all
 * DSFinV-K CSV exporters to ensure consistent output.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

/**
 * Common utilities for DSFinV-K CSV generation.
 */
object DsfinvkCsvUtils {
    /**
     * CSV field separator (semicolon as per German standard).
     */
    const val SEPARATOR = ";"

    /**
     * Line ending for CSV files.
     */
    const val LINE_ENDING = "\r\n"

    /**
     * Escapes a string value for CSV output.
     *
     * Wraps the value in double quotes and escapes internal quotes.
     *
     * @param value String value to escape
     * @return Escaped string suitable for CSV
     */
    fun escapeString(value: String?): String {
        // The GDPdU RecordDelimiter (CRLF) must never appear INSIDE a record — a multi-line
        // free-text field (cancellation reason, Belegtext) physically split the row for
        // importers that break on CRLF before honoring encapsulation (audit CONT-7).
        @Suppress("NAME_SHADOWING")
        val value = value?.replace("\r\n", " ")?.replace('\r', ' ')?.replace('\n', ' ')
        if (value == null) return "\"\""
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * Formats a decimal value for CSV output using German format.
     *
     * Uses comma as decimal separator per German standard.
     *
     * @param value Decimal value as string (with dot separator), or null
     * @return German-formatted decimal string, or empty string if null
     */
    fun formatDecimal(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        // m2: this is a plain `.` → `,` swap, so it silently corrupts anything that is not already
        // a bare dot-decimal. "1.234.56" (grouped) → "1,234,56"; "1.0E+10" (exponent) → "1,0E+10";
        // "13,43" (already comma) passes through unchanged, hiding a wrong upstream formatter. Every
        // caller feeds a toDecimalString()-produced value, so this only turns a future mistake into
        // a loud failure at the bad value instead of a malformed <Numeric> cell.
        require(value.matches(DECIMAL_INPUT)) {
            "formatDecimal expects a dot-decimal number (optional leading '-'), got: '$value'"
        }
        return value.replace(".", ",")
    }

    /** Optional leading minus, digits, at most one dot-decimal group. */
    private val DECIMAL_INPUT = Regex("""-?\d+(\.\d+)?""")

    /**
     * Formats a boolean value for CSV output.
     *
     * @param value Boolean value
     * @return "1" for true, "0" for false
     */
    fun formatBoolean(value: Boolean): String = if (value) "1" else "0"

    /**
     * Formats an optional long value for CSV output.
     *
     * @param value Optional long value
     * @return String representation or empty string if null
     */
    fun formatLong(value: Long?): String = value?.toString() ?: ""

    /**
     * Formats an optional int value for CSV output.
     *
     * @param value Optional int value
     * @return String representation or empty string if null
     */
    fun formatInt(value: Int?): String = value?.toString() ?: ""

    /**
     * Joins values with semicolon separator for CSV row.
     *
     * @param values List of string values
     * @return Semicolon-separated row string
     */
    fun joinRow(vararg values: String): String = values.joinToString(SEPARATOR)

    /**
     * Builds a CSV file from header and rows.
     *
     * @param header CSV header row
     * @param rows List of data rows
     * @return Complete CSV content as ByteArray (UTF-8)
     */
    fun buildCsv(
        header: String,
        rows: List<String>,
    ): ByteArray {
        val csv = StringBuilder()
        csv.append(header)
        csv.append(LINE_ENDING)
        rows.forEach { row ->
            csv.append(row)
            csv.append(LINE_ENDING)
        }
        return csv.toString().toByteArray(Charsets.UTF_8)
    }
}
