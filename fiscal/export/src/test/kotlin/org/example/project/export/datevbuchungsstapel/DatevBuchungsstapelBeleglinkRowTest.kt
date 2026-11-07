package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DatevBuchungsstapelBeleglinkRowTest {
    private fun input(belegLink: String?) =
        BuchungsstapelRowInput(
            umsatz = BigDecimal("19.00"),
            belegDate = LocalDate.of(2026, 1, 2),
            belegNr = "B1",
            buchungstext = "Verkauf",
            konto = 1600,
            gegenkonto = 8400,
            belegLink = belegLink,
        )

    @Test
    fun `beleglink populates column 20 with doubled quotes`() {
        val guid = "E0A08953-FBAA-4054-AF36-993D5D68F040"
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                input(DatevBeleglink.cellValue(guid)),
                sachkontenlaenge = 4,
            )
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(cells)
        val line = writer.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r', '\n')
        val field20 = line.split(";")[19]
        assertEquals("\"BEDI \"\"$guid\"\"\"", field20)
    }

    @Test
    fun `no beleglink leaves column 20 empty`() {
        val cells = DatevBuchungsstapelRowGenerator.generate(input(null), sachkontenlaenge = 4)
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(cells)
        val line = writer.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r', '\n')
        assertEquals("", line.split(";")[19])
    }
}
