package org.example.project.printer.core.emitter

import org.slf4j.LoggerFactory
import java.nio.charset.Charset

/**
 * Replaces typographic characters a printer code page cannot encode with plain ASCII before the
 * text is turned into bytes.
 *
 * The Western code pages (CP850 by default) have no en dash, curly quotes, ellipsis or bullet, so
 * `String.toByteArray` silently wrote `?` for them — `"TSE nicht erreichbar –"` printed as
 * `"TSE nicht erreichbar ?"`. A character is only replaced when the charset cannot encode it: a CJK
 * code page that has an en dash keeps it, and umlauts/ß stay byte-identical. Anything unencodable
 * that is not in [REPLACEMENTS] (e.g. `€` on CP850) is left to the encoder's own fallback and
 * logged at debug level.
 */
object PrintTextTransliterator {
    private val logger = LoggerFactory.getLogger(PrintTextTransliterator::class.java)

    private val REPLACEMENTS: Map<Char, String> =
        mapOf(
            '\u2013' to "-", // en dash
            '\u2014' to "-", // em dash
            '\u201E' to "\"", // low double quote
            '\u201C' to "\"", // left double quote
            '\u201D' to "\"", // right double quote
            '\u201A' to "'", // low single quote
            '\u2018' to "'", // left single quote
            '\u2019' to "'", // right single quote
            '\u2026' to "...", // ellipsis
            '\u00A0' to " ", // non-breaking space
            '\u2022' to "*", // bullet
        )

    /** [text] with every character [charset] cannot encode replaced by its ASCII equivalent. */
    fun transliterate(
        text: String,
        charset: Charset,
    ): String {
        val encoder = charset.newEncoder()
        if (encoder.canEncode(text)) return text
        return buildString(text.length) {
            for (c in text) {
                when {
                    encoder.canEncode(c) -> append(c)
                    c in REPLACEMENTS -> append(REPLACEMENTS.getValue(c))
                    else -> {
                        logger.debug("Character U+{} not encodable in {}; left to encoder fallback", "%04X".format(c.code), charset.name())
                        append(c)
                    }
                }
            }
        }
    }
}
