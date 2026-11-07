package org.example.project.export.datevstammdaten

import org.example.project.export.datev.DatevCharset
import java.io.ByteArrayOutputStream

/**
 * Writer for the Debitoren-/Kreditorenstamm CSV.
 *
 * Encoding rules — the same ones the Buchungsstapel obeys, because they belong
 * to the DATEV-Format and not to a file type:
 *
 * - ISO-8859-1 (CodePage 1252), no BOM. Every byte this class emits comes out
 *   of [DatevCharset], which is also the one place that decides what happens to
 *   a character the encoding cannot carry — see its doc for the fold set and
 *   for why anything outside it refuses the export instead of substituting a
 *   `?`. If the Stammdaten ever needs a fold the Buchungsstapel does not, it is
 *   added there, with a test, so both formats gain it.
 * - CRLF (`\r\n`) line terminators, including after the last line
 * - Semicolon (`;`) field separator
 *
 * **Quoting is per field, not per type — this is the one real difference from
 * [org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelCsvWriter].**
 * In the Buchungsstapel a value's type decides its quoting, so the writer can
 * infer it. Here it cannot: `Konto` and `Verknüpftes OPOS-Konto` are *unquoted*
 * digit strings, while `Adressatentyp` and `Kennz. Haupt-Bankverb. 1` are
 * single digits that *are* quoted. Two fields, indistinguishable by value,
 * quoted differently. So the caller states the intent through the [Cell] kind
 * and the writer never guesses.
 *
 * Usage:
 * ```
 * val writer = DatevStammdatenCsvWriter()
 * writer.writeLine(listOf(Cell.Digits("10000"), Cell.Str("Metro AG")))
 * val bytes = writer.toByteArray()
 * ```
 */
class DatevStammdatenCsvWriter {
    private val buf = ByteArrayOutputStream()

    sealed class Cell {
        /**
         * Text field — always wrapped in double quotes, **including when empty**
         * (`""`). An embedded `"` is doubled per RFC 4180, which is how DATEV
         * reads a quoted field.
         */
        data class Str(
            val value: String,
        ) : Cell()

        /**
         * Unquoted digit string, emitted verbatim so a leading zero survives.
         *
         * A [kotlin.Int] cannot carry the pad: an account number written as
         * `"0100"` would silently become `100` and book against a different
         * account. The call site is responsible for the exact width.
         */
        data class Digits(
            val value: String,
        ) : Cell()

        /** Unquoted integer, for the header's numeric fields. */
        data class Int(
            val value: kotlin.Int,
        ) : Cell()

        /**
         * Nothing at all between the separators — no content, no quotes.
         *
         * Deliberately distinct from `Str("")`, which emits `""`. DATEV
         * distinguishes an absent field from a present empty one, so the two
         * cannot be collapsed.
         */
        object Empty : Cell()
    }

    fun writeLine(cells: List<Cell>) {
        val sb = StringBuilder(cells.size * 8)
        cells.forEachIndexed { i, cell ->
            if (i > 0) sb.append(';')
            when (cell) {
                is Cell.Str -> {
                    sb.append('"')
                    sb.append(escapeForQuotedField(DatevCharset.toLatin1(cell.value, fieldName(i))))
                    sb.append('"')
                }
                is Cell.Digits -> sb.append(cell.value)
                is Cell.Int -> sb.append(cell.value)
                Cell.Empty -> Unit
            }
        }
        sb.append("\r\n")
        // Written in one go, after every cell has passed the charset gate: a
        // refused line must leave no half-written row behind it.
        buf.write(DatevCharset.encode(sb.toString()))
    }

    fun toByteArray(): ByteArray = buf.toByteArray()

    /**
     * Makes [value] safe to sit inside `"..."`.
     *
     * A control character becomes a **space**, not nothing: a supplier address
     * held as `"Musterweg 1\nBerlin"` would otherwise arrive as
     * `"Musterweg 1Berlin"`, which reads as a real address and so is worse than
     * an obviously spaced one. This also drains U+0080..U+009F, the range where
     * ISO-8859-1 and CodePage 1252 disagree, so the row reads identically under
     * either interpretation of DATEV's "ISO-8859-1 / CodePage 1252".
     */
    private fun escapeForQuotedField(value: String): String {
        val sb = StringBuilder(value.length)
        value.forEach { ch ->
            when {
                ch == '"' -> sb.append("\"\"")
                ch.isISOControl() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** DATEV field numbers are 1-based; the cell list is 0-based. */
    private fun fieldName(index: kotlin.Int): String = "field ${index + 1}"
}
