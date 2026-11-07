package org.example.project.banking.mt940

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Turns MT940 bytes into an ordered list of [Mt940Tag].
 *
 * ### Encoding
 * UTF-8 is tried with a **strict** decoder and ISO-8859-1 is the fallback. Lenient UTF-8 decoding
 * would be the wrong default: it substitutes `U+FFFD` for every bad byte, so "Müller GmbH" becomes
 * permanent garbage instead of being re-read in the encoding German banks actually emit. Trying
 * UTF-8 first still matters — some exports are UTF-8, and ISO-8859-1 never fails, so guessing that
 * way round would mojibake them.
 *
 * ### Continuation lines join with no separator
 * Banks wrap `?2x` subfields at a fixed width, cutting values mid-token: an IBAN routinely straddles
 * a line break. Inserting a space or newline when rejoining corrupts every such value, and the
 * corruption is invisible — the field still looks like an IBAN, it is simply the wrong one.
 */
object Mt940Tokenizer {
    private val TAG_LINE = Regex("""^:(\d{2}[A-Z]?):(.*)$""")

    fun tokenize(bytes: ByteArray): List<Mt940Tag> {
        val text = decode(bytes)
        val body = unwrapBlocks(text)

        val tags = mutableListOf<Mt940Tag>()
        val current = StringBuilder()
        var id: String? = null
        var firstLine = ""

        fun flush() {
            id?.let { tags += Mt940Tag(it, current.toString().trimEnd(), firstLine.trimEnd()) }
            current.setLength(0)
            firstLine = ""
        }

        for (line in body.split('\n')) {
            val clean = line.removeSuffix("\r")
            // A lone `-` is SWIFT's end-of-message marker. Without this it is treated as a
            // continuation and glued — with no separator — onto the last tag, turning a closing
            // balance of `12345,98` into `12345,98-`, which then fails to parse. Anything after it
            // is a trailing fragment and must not become entries of this statement.
            if (clean.trim() == "-") break
            val match = TAG_LINE.matchEntire(clean)
            if (match != null) {
                flush()
                id = match.groupValues[1]
                firstLine = match.groupValues[2]
                current.append(match.groupValues[2])
            } else if (id != null) {
                // No separator. See the class KDoc — this is load-bearing.
                current.append(clean)
            }
        }
        flush()
        return tags
    }

    /**
     * Strips SWIFT block framing when present. Block 4 holds the message text; the trailing `-}`
     * terminates it. A plain online-banking `.sta` download has no framing at all, so an absent
     * block 4 means "the whole file is the body" rather than "empty".
     */
    private fun unwrapBlocks(text: String): String {
        val start = text.indexOf("{4:")
        if (start < 0) return text
        val body = text.substring(start + 3)
        val end = body.lastIndexOf("-}")
        return if (end >= 0) body.substring(0, end) else body
    }

    private fun decode(bytes: ByteArray): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
}
