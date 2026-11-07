package org.example.project.export.datevstammdaten

import org.example.project.export.datevstammdaten.model.StammdatenFileConfig
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Row 1 of the file.
 *
 * The values that identify the format are asserted literally, because getting one wrong produces a
 * file DATEV rejects wholesale with an error that does not name the field.
 */
class DatevStammdatenMetadataHeaderTest {
    private val config =
        StammdatenFileConfig(
            consultantNumber = "29098",
            clientNumber = "55003",
            wirtschaftsjahrBeginn = LocalDate.of(2026, 1, 1),
            sachkontenlaenge = 4,
            bezeichnung = "Kunden und Lieferanten",
            skr = "03",
        )

    @Test
    fun `it declares category 16, Debitoren slash Kreditoren, version 5`() {
        val cells = DatevStammdatenMetadataHeader.build(config, LocalDateTime.of(2026, 9, 2, 14, 6, 59))

        assertEquals(31, cells.size, "the Stammdaten header is 31 fields")
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("EXTF"), cells[0])
        assertEquals(DatevStammdatenCsvWriter.Cell.Int(700), cells[1])
        assertEquals(DatevStammdatenCsvWriter.Cell.Int(16), cells[2])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("Debitoren/Kreditoren"), cells[3])
        assertEquals(DatevStammdatenCsvWriter.Cell.Int(5), cells[4])
    }

    /**
     * Master data has no period. A Buchungsstapel carries one here, and copying that row is the
     * likeliest way to get this format wrong.
     */
    @Test
    fun `it leaves the period fields empty`() {
        val cells = DatevStammdatenMetadataHeader.build(config, LocalDateTime.of(2026, 9, 2, 14, 6, 59))

        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, cells[14], "field 15 - Datum von")
        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, cells[15], "field 16 - Datum bis")
    }

    /** The fields that only a Bewegungsdaten format fills. */
    @Test
    fun `it leaves the booking-only fields empty`() {
        val cells = DatevStammdatenMetadataHeader.build(config, LocalDateTime.of(2026, 9, 2, 14, 6, 59))

        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, cells[18], "field 19 - Buchungstyp")
        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, cells[19], "field 20 - Rechnungslegungszweck")
        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, cells[20], "field 21 - Festschreibung")
    }

    /** Written through the real writer, so the header is proven as bytes and not only as cells. */
    @Test
    fun `the written row matches DATEV's documented shape`() {
        val writer = DatevStammdatenCsvWriter()
        writer.writeLine(DatevStammdatenMetadataHeader.build(config, LocalDateTime.of(2026, 1, 30, 14, 6, 59, 583_000_000)))

        val line = writer.toByteArray().toString(Charsets.ISO_8859_1)

        assertEquals(true, line.startsWith("\"EXTF\";700;16;\"Debitoren/Kreditoren\";5;20260130140659583;;"), line)
        assertEquals(true, line.endsWith("\r\n"), "every line is CRLF terminated")
    }
}
