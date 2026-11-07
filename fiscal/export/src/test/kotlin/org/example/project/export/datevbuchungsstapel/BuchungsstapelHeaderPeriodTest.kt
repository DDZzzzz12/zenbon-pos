package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The metadata header's `Datum vom`/`Datum bis` must cover every row the file carries.
 *
 * A receipt handed in late keeps its own Beleg date while the period it books into is decided by
 * the booking time (`e1240a3c2`), so a row's Belegdatum can precede the requested export range.
 * Until this was fixed the header still stated the requested range, and the file contradicted its
 * own rows — E2E-2026-09-22 finding R3-1.
 */
class BuchungsstapelHeaderPeriodTest {
    private val config =
        BuchungsstapelFileConfig(
            consultantNumber = "29098",
            clientNumber = "55003",
            wirtschaftsjahrBeginn = LocalDate.of(2026, 1, 1),
            sachkontenlaenge = 4,
            festschreibung = false,
            startDate = LocalDate.of(2026, 9, 22),
            endDate = LocalDate.of(2026, 9, 22),
            bezeichnung = "Kassenbuch 2026-09-22",
        )

    private val fixedNow = LocalDateTime.of(2026, 9, 22, 18, 9, 50)

    private fun row(belegDate: LocalDate) =
        BuchungsstapelRowInput(
            umsatz = BigDecimal("23.80"),
            belegDate = belegDate,
            belegNr = "2026-09-0029",
            buchungstext = "R3 Lieferantenrechnung August",
            konto = 1000,
            gegenkonto = 3400,
        )

    /** Cell 15 of the metadata row, 1-based as DATEV counts them, is `Datum vom`; 16 is `Datum bis`. */
    private fun headerPeriod(rows: List<BuchungsstapelRowInput>): Pair<String, String> {
        val text = DatevBuchungsstapelExportBuilder.build(config, rows, fixedNow).toString(Charsets.ISO_8859_1)
        val cells = text.lineSequence().first().split(";")
        return cells[14] to cells[15]
    }

    @Test
    fun `a row dated before the requested period widens the header`() {
        val period = headerPeriod(listOf(row(LocalDate.of(2026, 8, 13)), row(LocalDate.of(2026, 9, 22))))

        assertEquals("20260813" to "20260922", period)
    }

    @Test
    fun `the requested period is kept when every row falls inside it`() {
        val period = headerPeriod(listOf(row(LocalDate.of(2026, 9, 22))))

        assertEquals("20260922" to "20260922", period)
    }

    @Test
    fun `an empty file keeps the requested period`() {
        val period = headerPeriod(emptyList())

        assertEquals("20260922" to "20260922", period)
    }
}
