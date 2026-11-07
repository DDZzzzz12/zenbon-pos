package org.example.project.printer.core.emitter

import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.printer.core.enums.CodePage
import org.example.project.utils.formatter.tseSigningNoteLines
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A typographic character the code page lacks printed as `?` (real Beleg: "TSE nicht erreichbar ?").
 * The encoder boundary now transliterates it; everything the code page *can* encode is untouched.
 */
class PrintTextTransliteratorTest {
    private val cp850 = CodePage.CP850.charset

    private fun encode(text: String): ByteArray = ByteArrayBuilder(cp850).append(text).build()

    @Test
    fun `the TSE outage line prints a hyphen, not a question mark`() {
        val note =
            TseSigningNoteDto(
                pending = true,
                reasonCode = "TSE_UNAVAILABLE",
                reasonText = "",
                queuedAt = "2026-10-04T12:00:00",
            )
        val line = tseSigningNoteLines(note).first()
        val printed = String(encode(line), cp850)
        assertEquals("TSE nicht erreichbar -", printed)
        assertFalse('?' in printed)
    }

    @Test
    fun `umlauts sharp s and euro are byte-identical to the plain encoder`() {
        val text = "ÄÖÜäöüß \u20AC Grüße"
        assertContentEquals(text.toByteArray(cp850), encode(text))
    }

    @Test
    fun `every mapped character is transliterated`() {
        val cases =
            mapOf(
                "\u2013" to "-",
                "\u2014" to "-",
                "\u201E" to "\"",
                "\u201C" to "\"",
                "\u201D" to "\"",
                "\u201A" to "'",
                "\u2018" to "'",
                "\u2019" to "'",
                "\u2026" to "...",
                "\u2022" to "*",
            )
        cases.forEach { (input, expected) ->
            assertEquals("a${expected}b", String(encode("a${input}b"), cp850), "U+%04X".format(input[0].code))
        }
    }

    @Test
    fun `non-breaking space becomes a plain space only where the code page lacks it`() {
        // CP850 has NBSP (0xFF), so it is kept byte-identical there; ISO-646-ish ASCII has none.
        assertContentEquals("\u00A0".toByteArray(cp850), encode("\u00A0"))
        assertEquals(" ", PrintTextTransliterator.transliterate("\u00A0", Charsets.US_ASCII))
    }

    @Test
    fun `a code page that can encode the dash keeps it`() {
        assertEquals("a\u2013b", PrintTextTransliterator.transliterate("a\u2013b", CodePage.GB18030.charset))
    }

    @Test
    fun `an unmapped unencodable character is left to the encoder fallback`() {
        assertEquals("\u20AC", PrintTextTransliterator.transliterate("\u20AC", cp850))
    }
}
