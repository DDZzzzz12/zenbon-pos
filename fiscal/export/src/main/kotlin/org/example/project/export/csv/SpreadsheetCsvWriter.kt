package org.example.project.export.csv

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * CSV for a spreadsheet, not for DATEV.
 *
 * UTF-8 **with a BOM**: Excel reads a BOM-less UTF-8 file as the system code page and mangles every
 * umlaut. This is deliberately not the encoding the DATEV writers use — those are ISO-8859-1 because
 * DATEV's importer requires it — and the two must not be unified. See the custom export design spec.
 *
 * Semicolon-delimited and CRLF-terminated, which is what German Excel expects. The writer takes
 * already-rendered strings and does not format numbers — the decimal comma is applied by whoever
 * renders a value, since only they know the field's type. That same caller states each cell's kind
 * as a [CsvValue], which is what decides whether the formula guard applies.
 */
class SpreadsheetCsvWriter {
    private val buf = ByteArrayOutputStream().apply { write(BOM) }

    fun writeRow(values: List<CsvValue>) {
        buf.write(values.joinToString(DELIMITER) { escape(it) }.toByteArray(StandardCharsets.UTF_8))
        buf.write(LINE_END.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Convenience for a caller with no numeric columns. Every cell becomes [CsvValue.Text], the safe
     * default — a caller that wants the guard skipped must say so explicitly, never by omission.
     *
     * Needs [JvmName] because `List<String>` and `List<CsvValue>` erase to the same JVM signature.
     */
    @JvmName("writeTextRow")
    fun writeRow(values: List<String>) = writeRow(values.map { CsvValue.Text(it) })

    fun toByteArray(): ByteArray = buf.toByteArray()

    /**
     * Quotes a value when it would otherwise change the shape of the file, and neutralises one that
     * a spreadsheet would evaluate.
     *
     * The formula guard is not cosmetic: a cell beginning `=`, `+`, `-` or `@` is executed on open,
     * so an operator-entered supplier name can become a formula in the recipient's spreadsheet. It
     * runs before the quoting decision, so the `'` lands inside the quotes when both apply.
     *
     * Quoting applies to both kinds — a delimiter or a newline breaks the file's shape whatever the
     * cell means. Only the guard is skipped for [CsvValue.Numeric].
     */
    private fun escape(value: CsvValue): String {
        val guarded =
            when (value) {
                is CsvValue.Numeric -> value.rendered
                is CsvValue.Text -> guardFormula(value.value)
            }
        val needsQuotes = guarded.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${guarded.replace("\"", "\"\"")}\"" else guarded
    }

    private fun guardFormula(value: String): String = if (value.isNotEmpty() && value.first() in FORMULA_LEADS) "'$value" else value

    private companion object {
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        const val DELIMITER = ";"
        const val LINE_END = "\r\n"
        val FORMULA_LEADS = charArrayOf('=', '+', '-', '@')
    }
}
