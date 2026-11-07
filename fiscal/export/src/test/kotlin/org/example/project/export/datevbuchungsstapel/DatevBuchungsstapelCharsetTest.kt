package org.example.project.export.datevbuchungsstapel

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatevBuchungsstapelCharsetTest {
    @Test
    fun `German text encodes to single Latin-1 bytes`() {
        val bytes = DatevBuchungsstapelCharset.encode("äöüß")
        assertContentEquals(
            byteArrayOf(0xE4.toByte(), 0xF6.toByte(), 0xFC.toByte(), 0xDF.toByte()),
            bytes,
        )
    }

    @Test
    fun `en dash folds to a hyphen`() {
        // The export's own Bezeichnung is "Kassenbuch <von>–<bis>", so this
        // fires on every single file; rejecting it would refuse them all.
        assertEquals(
            "Kassenbuch 2026-04-01-2026-04-30",
            DatevBuchungsstapelCharset.toLatin1("Kassenbuch 2026-04-01–2026-04-30", "field 17"),
        )
    }

    @Test
    fun `typographic quotes fold to an apostrophe, never to a double quote`() {
        val folded = DatevBuchungsstapelCharset.toLatin1("„Café Müller“", "field 14")
        assertEquals("'Café Müller'", folded)
        assertTrue(!folded.contains('"'), "a raw double quote would break the CSV field")
    }

    @Test
    fun `euro sign folds to EUR because ISO-8859-1 has no code point for it`() {
        assertEquals("Pfand 0,25 EUR", DatevBuchungsstapelCharset.toLatin1("Pfand 0,25 €", "field 14"))
    }

    @Test
    fun `an un-encodable character is rejected, naming the character and the value`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                DatevBuchungsstapelCharset.toLatin1("Koşer Handel GmbH", "field 14 (Buchungstext)")
            }
        val message = ex.message ?: ""
        assertTrue(message.contains("ş"), "message must name the offending character: $message")
        assertTrue(message.contains("U+015F"), "message must name the code point: $message")
        assertTrue(message.contains("Koşer Handel GmbH"), "message must quote the field value: $message")
        assertTrue(message.contains("field 14 (Buchungstext)"), "message must name the field: $message")
    }

    @Test
    fun `the writer refuses a row whose Buchungstext cannot be encoded`() {
        val writer = DatevBuchungsstapelCsvWriter()
        val ex =
            assertFailsWith<IllegalArgumentException> {
                writer.writeLine(listOf(DatevBuchungsstapelCsvWriter.Cell.Str("Koşer Handel")))
            }
        assertTrue((ex.message ?: "").contains("field 1"), "message must carry the 1-based DATEV field number")
        // A refused line must leave nothing half-written behind it.
        assertContentEquals(ByteArray(0), writer.toByteArray())
    }

    @Test
    fun `ISO-8859-1 output is byte-identical to CodePage 1252 for everything this writer emits`() {
        // DATEV documents the default as "ISO-8859-1 / CodePage 1252". The two
        // differ only in 0x80..0x9F, which the transliteration table drains, so
        // the file reads the same whichever of the two the advisor's software
        // assumes. This test is the standing guard on that claim.
        val sample = DatevBuchungsstapelCharset.toLatin1("Getränke-Müller GmbH – Straße 5 € „test“", "field 14")
        assertContentEquals(
            sample.toByteArray(Charset.forName("windows-1252")),
            sample.toByteArray(StandardCharsets.ISO_8859_1),
        )
    }
}
