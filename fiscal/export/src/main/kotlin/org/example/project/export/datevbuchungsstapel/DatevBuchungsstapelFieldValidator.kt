package org.example.project.export.datevbuchungsstapel

/**
 * Field validation and sanitization for Buchungsstapel CSV output.
 *
 * Mirrors the responsibilities of [org.example.project.export.datevxml.DatevXmlFieldValidator]
 * but for the EXTF CSV format's specific constraints: digit-only account
 * numbers, 60-char Buchungstext, 36-char Belegfeld/KOST, no quoted
 * separators or CR/LF inside strings.
 */
object DatevBuchungsstapelFieldValidator {
    const val MAX_BUCHUNGSTEXT = 60
    const val MAX_BELEGFELD = 36
    const val MAX_KOST = 36

    /**
     * Strips double quotes, converts semicolons (the CSV separator) to commas,
     * removes CR, replaces LF with space, and trims.
     */
    fun sanitizeText(input: String): String =
        input
            .replace("\"", "")
            .replace(";", ",")
            .replace("\r", "")
            .replace("\n", " ")
            .trim()

    fun truncate(
        input: String,
        max: Int,
    ): String = if (input.length <= max) input else input.substring(0, max)

    fun sanitizeAndTruncate(
        input: String,
        max: Int,
    ): String = truncate(sanitizeText(input), max)

    fun validateAccountNumber(
        value: Int,
        sachkontenlaenge: Int,
        fieldName: String,
    ) {
        require(value >= 0) { "$fieldName must be non-negative, got $value" }
        val asString = value.toString()
        require(asString.length <= sachkontenlaenge) {
            "$fieldName ($value) exceeds Sachkontenlänge=$sachkontenlaenge"
        }
    }

    fun validateConsultantNumber(value: String) {
        require(value.isNotBlank() && value.all { it.isDigit() }) {
            "DATEV Beraternummer must be non-blank digit-only, got '$value'"
        }
    }

    fun validateClientNumber(value: String) {
        require(value.isNotBlank() && value.all { it.isDigit() }) {
            "DATEV Mandantennummer must be non-blank digit-only, got '$value'"
        }
    }
}
