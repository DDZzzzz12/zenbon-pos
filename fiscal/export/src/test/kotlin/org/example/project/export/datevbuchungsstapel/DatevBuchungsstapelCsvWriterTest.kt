package org.example.project.export.datevbuchungsstapel

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatevBuchungsstapelCsvWriterTest {
    @Test
    fun `writes ISO-8859-1 without BOM and CRLF terminator`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(listOf(DatevBuchungsstapelCsvWriter.Cell.Int(100), DatevBuchungsstapelCsvWriter.Cell.Str("Hallo")))
        val bytes = writer.toByteArray()
        assertTrue(bytes[0] != 0xEF.toByte(), "should not start with UTF-8 BOM")
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        assertTrue(text.endsWith("\r\n"), "should end with CRLF")
    }

    @Test
    fun `Int cells unquoted, IntOrEmpty null emits nothing between separators`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(
            listOf(
                DatevBuchungsstapelCsvWriter.Cell.Int(1600),
                DatevBuchungsstapelCsvWriter.Cell.IntOrEmpty(null),
                DatevBuchungsstapelCsvWriter.Cell.Int(8400),
            ),
        )
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r\n")
        assertEquals("1600;;8400", text)
    }

    @Test
    fun `Str cells always double-quoted even when empty`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(
            listOf(
                DatevBuchungsstapelCsvWriter.Cell.Str("Verkauf"),
                DatevBuchungsstapelCsvWriter.Cell.Str(""),
            ),
        )
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r\n")
        assertEquals("\"Verkauf\";\"\"", text)
    }

    @Test
    fun `StrOrEmpty null emits nothing between separators`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(
            listOf(
                DatevBuchungsstapelCsvWriter.Cell.Str("A"),
                DatevBuchungsstapelCsvWriter.Cell.StrOrEmpty(null),
                DatevBuchungsstapelCsvWriter.Cell.Str("B"),
            ),
        )
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r\n")
        assertEquals("\"A\";;\"B\"", text)
    }

    @Test
    fun `Empty cells emit nothing between separators`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(
            listOf(
                DatevBuchungsstapelCsvWriter.Cell.Int(1),
                DatevBuchungsstapelCsvWriter.Cell.Empty,
                DatevBuchungsstapelCsvWriter.Cell.Empty,
                DatevBuchungsstapelCsvWriter.Cell.Int(2),
            ),
        )
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r\n")
        assertEquals("1;;;2", text)
    }

    @Test
    fun `Amount uses comma decimal with 2 decimals, half-up rounding`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(listOf(DatevBuchungsstapelCsvWriter.Cell.Amount(BigDecimal("100.185"))))
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r\n")
        assertEquals("100,19", text)
    }

    @Test
    fun `Amount handles integer input`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(listOf(DatevBuchungsstapelCsvWriter.Cell.Amount(BigDecimal("7"))))
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r\n")
        assertEquals("7,00", text)
    }

    @Test
    fun `appendRaw writes bytes without quoting or terminator`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.appendRaw("Col1;Col2;Col3")
        writer.appendLineTerminator()
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1)
        assertEquals("Col1;Col2;Col3\r\n", text)
    }

    @Test
    fun `multiple lines accumulate correctly with CRLF between each`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(listOf(DatevBuchungsstapelCsvWriter.Cell.Int(1)))
        writer.writeLine(listOf(DatevBuchungsstapelCsvWriter.Cell.Int(2)))
        val text = String(writer.toByteArray(), StandardCharsets.ISO_8859_1)
        assertEquals("1\r\n2\r\n", text)
    }

    @Test
    fun `German umlauts and eszett are written as single Latin-1 bytes`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(listOf(DatevBuchungsstapelCsvWriter.Cell.Str("Getr\u00e4nke-M\u00fcller GmbH, Stra\u00dfe")))
        val bytes = writer.toByteArray()

        // Byte-level on purpose: decoding the output re-applies the writer's own
        // charset assumption and would pass under any encoding. DATEV reads the
        // file as CP1252, so "\u00e4" must be the single byte 0xE4 on disk.
        val expected =
            "\"Getr\u00e4nke-M\u00fcller GmbH, Stra\u00dfe\"\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        assertContentEquals(expected, bytes)
    }

    @Test
    fun `column-name umlauts survive appendRaw as single Latin-1 bytes`() {
        val writer = DatevBuchungsstapelCsvWriter()
        writer.appendRaw("BU-Schl\u00fcssel;Gesch\u00e4ftspartnerbank;St\u00fcck")
        val expected = "BU-Schl\u00fcssel;Gesch\u00e4ftspartnerbank;St\u00fcck".toByteArray(StandardCharsets.ISO_8859_1)
        assertContentEquals(expected, writer.toByteArray())
    }
}
