package org.example.project.export.datevbuchungsstapel

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Writer for the Buchungsstapel CSV.
 *
 * Encoding rules:
 *
 * - ISO-8859-1 (CodePage 1252), no BOM — see [DatevBuchungsstapelCharset] for
 *   why, and for what happens to a character it cannot carry. Short version:
 *   DATEV's character-set page
 *   (<https://developer.datev.de/de/file-format/details/datev-format/character-set>)
 *   documents ISO-8859-1 / CP1252 as the default and accepts UTF-8 only with a
 *   BOM, and only on import paths we do not control.
 * - CRLF (`\r\n`) line terminators, including after the last line
 * - Semicolon (`;`) field separator
 * - Strings wrapped in `"..."`, quoted even when empty (`""`)
 * - Numerics unquoted (empty numeric → nothing between separators)
 * - Decimal separator comma (`,`) for amounts
 *
 * Usage:
 * ```
 * val writer = DatevBuchungsstapelCsvWriter()
 * writer.writeLine(listOf(Cell.Int(1600), Cell.Str("Verkauf")))
 * val bytes = writer.toByteArray()
 * ```
 */
class DatevBuchungsstapelCsvWriter {
    private val buf = ByteArrayOutputStream()

    sealed class Cell {
        /** Required non-null integer — always emitted. */
        data class Int(
            val value: kotlin.Int,
        ) : Cell()

        /** Optional integer — emits nothing between separators when null. */
        data class IntOrEmpty(
            val value: kotlin.Int?,
        ) : Cell()

        /**
         * Fixed-width unquoted digit string, emitted verbatim.
         *
         * Exists because [Int] cannot carry a leading zero: DATEV's date and
         * numeric-code fields are fixed-width digit strings, not integers, and
         * routing one through [Int] silently drops the pad (`"02012026"` →
         * `2012026`). The call site is responsible for producing the exact width.
         */
        data class Digits(
            val value: String,
        ) : Cell()

        /** Required non-null [BigDecimal] formatted as `x,xx`. */
        data class Amount(
            val value: BigDecimal,
        ) : Cell()

        /** Optional [BigDecimal] — nothing between separators when null. */
        data class AmountOrEmpty(
            val value: BigDecimal?,
        ) : Cell()

        /**
         * Required string — always wrapped in double quotes, even when empty.
         * Input is written verbatim inside the quotes; call-site is
         * responsible for sanitizing embedded `"` and CRLF characters.
         */
        data class Str(
            val value: String,
        ) : Cell()

        /**
         * Optional string — quoted when non-null, emits nothing between
         * separators when null. Used for fields that are empty by convention
         * of the format but not present as an empty quoted string.
         */
        data class StrOrEmpty(
            val value: String?,
        ) : Cell()

        /** Always empty (no content, no quotes). */
        object Empty : Cell()
    }

    fun writeLine(cells: List<Cell>) {
        val sb = StringBuilder(cells.size * 4)
        cells.forEachIndexed { i, cell ->
            if (i > 0) sb.append(';')
            when (cell) {
                is Cell.Int -> sb.append(cell.value)
                is Cell.IntOrEmpty -> cell.value?.let { sb.append(it) }
                is Cell.Digits -> sb.append(cell.value)
                is Cell.Amount -> sb.append(formatAmount(cell.value))
                is Cell.AmountOrEmpty -> cell.value?.let { sb.append(formatAmount(it)) }
                is Cell.Str -> {
                    sb.append('"')
                    sb.append(DatevBuchungsstapelCharset.toLatin1(cell.value, fieldName(i)))
                    sb.append('"')
                }
                is Cell.StrOrEmpty ->
                    cell.value?.let {
                        sb.append('"')
                        sb.append(DatevBuchungsstapelCharset.toLatin1(it, fieldName(i)))
                        sb.append('"')
                    }
                Cell.Empty -> Unit
            }
        }
        sb.append("\r\n")
        buf.write(DatevBuchungsstapelCharset.encode(sb.toString()))
    }

    /**
     * Append a pre-formatted raw line (used for the 125-field column-names row).
     *
     * Goes through the same charset gate as a cell: that row is not ASCII —
     * it carries `BU-Schl\u00fcssel`, `Gesch\u00e4ftspartnerbank`, `St\u00fcck`
     * and five more umlauts — so it was mangled in every exported file, not
     * only in files whose booking text happened to contain one.
     */
    fun appendRaw(text: String) {
        buf.write(DatevBuchungsstapelCharset.encode(DatevBuchungsstapelCharset.toLatin1(text, "column-names row")))
    }

    /** Append a CRLF terminator (used after [appendRaw]). */
    fun appendLineTerminator() {
        buf.write(DatevBuchungsstapelCharset.encode("\r\n"))
    }

    fun toByteArray(): ByteArray = buf.toByteArray()

    /** DATEV field numbers are 1-based; the cell list is 0-based. */
    private fun fieldName(index: kotlin.Int): String = "field ${index + 1}"

    private fun formatAmount(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
