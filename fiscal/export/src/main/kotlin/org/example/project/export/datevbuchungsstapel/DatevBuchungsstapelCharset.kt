package org.example.project.export.datevbuchungsstapel

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The character set an `EXTF_Buchungsstapel.csv` is written in, and the one
 * place that decides what happens to a character it cannot carry.
 *
 * DATEV's format documentation
 * (<https://developer.datev.de/de/file-format/details/datev-format/character-set>)
 * states that the default character set for DATEV-Format files is
 * ISO-8859-1 / CodePage 1252, and that UTF-8, UTF-16 and UTF-32 are accepted
 * **only with a byte-order mark — UTF-8 included**. It further notes that
 * Unicode works only through the manual import in DATEV Rechnungswesen or the
 * `accounting:extf-files` online API, and that the KrStaPv app supports no
 * Unicode at all.
 *
 * We do not control which of those import paths the tax advisor uses, so the
 * file is written in [StandardCharsets.ISO_8859_1] — the one encoding every
 * path reads. ISO-8859-1 and CodePage 1252 agree on every byte this writer can
 * emit, because [toLatin1] resolves the handful of typographic characters that
 * exist only in CP1252 before encoding; the output is therefore correct under
 * either reading of DATEV's "ISO-8859-1 / CodePage 1252".
 *
 * Characters outside that repertoire get one of two treatments, deliberately:
 *
 * - A **closed set of typographic variants of ASCII punctuation** ([TRANSLITERATIONS])
 *   is folded to its plain equivalent. These carry no information — an en dash
 *   in a date range means what a hyphen means — and one of them, the en dash in
 *   the export's own `Bezeichnung` ("Kassenbuch <von>–<bis>"), is produced by
 *   our own code on every export, so rejecting it would refuse every file.
 * - **Everything else throws.** A `?` substitution would put a corrupted
 *   supplier name into an accounting handover with nothing anywhere to signal
 *   it; nobody downstream can tell a mangled name from a correctly read one.
 *   A refused export names the character and the value, is visible the second
 *   it happens, and is fixed by editing one Belegtext. This matches how the
 *   rest of this export already behaves — an unresolvable account refuses the
 *   whole file rather than book a wrong one.
 */
object DatevBuchungsstapelCharset {
    /** The encoding of the emitted file. See the class doc for why. */
    val CHARSET: Charset = StandardCharsets.ISO_8859_1

    /**
     * Typographic characters folded to their plain equivalent before encoding.
     *
     * Quotation marks fold to an apostrophe rather than to `"`: the CSV wraps
     * string fields in `"`, so injecting a raw double quote here would break
     * the field, not just the character.
     */
    private val TRANSLITERATIONS: Map<Char, String> =
        mapOf(
            '‐' to "-", // hyphen
            '‑' to "-", // non-breaking hyphen
            '‒' to "-", // figure dash
            '–' to "-", // en dash — emitted by our own Bezeichnung
            '—' to "-", // em dash
            '―' to "-", // horizontal bar
            '−' to "-", // minus sign
            '‘' to "'", // left single quote
            '’' to "'", // right single quote / typographic apostrophe
            '‚' to ",", // single low quote
            '‛' to "'", // reversed single quote
            '“' to "'", // left double quote
            '”' to "'", // right double quote
            '„' to "'", // double low quote (German opening quote)
            '…' to "...", // ellipsis
            '•' to "-", // bullet
            '€' to "EUR", // euro sign — CP1252 0x80, absent from ISO-8859-1
            'ẞ' to "SS", // capital sharp s
        )

    /**
     * Folds [value] into the ISO-8859-1 repertoire, or throws naming the
     * character that cannot be carried.
     *
     * @param fieldName how the field is identified to the operator in the
     *   failure message (e.g. `"field 14 (Buchungstext)"`).
     */
    fun toLatin1(
        value: String,
        fieldName: String,
    ): String {
        val sb = StringBuilder(value.length)
        value.forEach { ch ->
            val folded = TRANSLITERATIONS[ch]
            if (folded != null) {
                sb.append(folded)
                return@forEach
            }
            // ISO-8859-1 is exactly U+0000..U+00FF, so this is the encodability
            // test — no CharsetEncoder needed, and no per-call allocation.
            require(ch.code <= 0xFF) {
                "DATEV Buchungsstapel $fieldName contains '$ch' (U+%04X), which the DATEV character set ".format(ch.code) +
                    "(ISO-8859-1 / CodePage 1252) cannot represent. Value: \"$value\". " +
                    "Replace the character and export again."
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Encodes text that has already passed [toLatin1]. */
    fun encode(text: String): ByteArray = text.toByteArray(CHARSET)
}
