package org.example.project.export.datevstammdaten

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The byte-level contract with DATEV.
 *
 * Asserted as bytes rather than as a String, because the defect this guards against — writing UTF-8
 * where DATEV expects CP1252 — is invisible once the bytes are decoded back into a String. That is
 * precisely why the Buchungsstapel writer shipped the bug: every one of its tests decoded.
 */
class DatevStammdatenCsvWriterTest {
    @Test
    fun `an umlaut is written as one ISO-8859-1 byte, not two UTF-8 bytes`() {
        val writer = DatevStammdatenCsvWriter()
        writer.writeLine(listOf(DatevStammdatenCsvWriter.Cell.Str("Müller")))

        // 0xFC is 'ü' in ISO-8859-1. UTF-8 would emit 0xC3 0xBC and DATEV would render "MÃ¼ller".
        assertContentEquals(
            byteArrayOf(0x22, 0x4D, 0xFC.toByte(), 0x6C, 0x6C, 0x65, 0x72, 0x22, 0x0D, 0x0A),
            writer.toByteArray(),
        )
    }

    @Test
    fun `quoting is per cell kind, and the two empties differ`() {
        val writer = DatevStammdatenCsvWriter()
        writer.writeLine(
            listOf(
                DatevStammdatenCsvWriter.Cell.Digits("10000"),
                DatevStammdatenCsvWriter.Cell.Str("Metro AG"),
                DatevStammdatenCsvWriter.Cell.Str(""),
                DatevStammdatenCsvWriter.Cell.Empty,
            ),
        )

        assertEquals("10000;\"Metro AG\";\"\";\r\n", writer.toByteArray().toString(Charsets.ISO_8859_1))
    }

    /** DATEV doubles an embedded quote and forbids control characters inside a quoted field. */
    @Test
    fun `an embedded quote is doubled and a newline becomes a space`() {
        val writer = DatevStammdatenCsvWriter()
        writer.writeLine(listOf(DatevStammdatenCsvWriter.Cell.Str("A \"B\"\nC")))

        assertEquals("\"A \"\"B\"\" C\"\r\n", writer.toByteArray().toString(Charsets.ISO_8859_1))
    }

    /**
     * The refusal policy is [org.example.project.export.datev.DatevCharset]'s, not this writer's —
     * this only proves the writer routes through it rather than encoding bytes itself.
     *
     * 'š' (U+0161) is the probe because it is genuinely refused: ISO-8859-1 is exactly
     * U+0000..U+00FF, so U+0161 falls outside it, and it is not in the charset's fold table either
     * (that table holds only typographic variants of ASCII punctuation, plus '€' and 'ẞ').
     */
    @Test
    fun `a character outside Latin-1 is rejected rather than substituted`() {
        val writer = DatevStammdatenCsvWriter()

        val error =
            runCatching { writer.writeLine(listOf(DatevStammdatenCsvWriter.Cell.Str("Košice"))) }
                .exceptionOrNull()

        assertEquals(true, error is IllegalArgumentException, "expected a refusal, got $error")
    }
}
