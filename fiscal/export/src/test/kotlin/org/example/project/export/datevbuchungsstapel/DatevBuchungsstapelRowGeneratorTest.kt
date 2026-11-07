package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelCsvWriter.Cell
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatevBuchungsstapelRowGeneratorTest {
    private fun sampleInput(
        umsatz: BigDecimal = BigDecimal("100.00"),
        buchungstext: String = "Barverkauf",
        belegNr: String = "1234",
        gegenkonto: Int? = 8400,
    ) = BuchungsstapelRowInput(
        umsatz = umsatz,
        belegDate = LocalDate.of(2026, 4, 21),
        belegNr = belegNr,
        buchungstext = buchungstext,
        konto = 1000,
        gegenkonto = gegenkonto,
    )

    @Test
    fun `Belegdatum is zero-padded to 8 digits for single-digit days and months`() {
        // Regression: the generator used to do .format(ddMMyyyy).toInt(), and the writer
        // appends an Int unpadded — so every day 1..9 emitted a 7-digit Belegdatum.
        // 22 of 68 rows in a real shipped export were malformed.
        val cases =
            mapOf(
                LocalDate.of(2026, 1, 2) to "02012026",
                LocalDate.of(2026, 11, 5) to "05112026",
                LocalDate.of(2026, 1, 1) to "01012026",
                LocalDate.of(2026, 4, 21) to "21042026",
            )
        for ((date, expected) in cases) {
            val cells =
                DatevBuchungsstapelRowGenerator.generate(
                    BuchungsstapelRowInput(
                        umsatz = BigDecimal("19.00"),
                        belegDate = date,
                        belegNr = "B1",
                        buchungstext = "Verkauf",
                        konto = 1000,
                        gegenkonto = 8400,
                    ),
                    sachkontenlaenge = 4,
                )
            assertEquals(Cell.Digits(expected), cells[9], "Belegdatum for $date")
        }
    }

    /**
     * „Konto automatisch zuordnen" off: the Gegenkonto field is written truly EMPTY — no `0`, no
     * quotes — and the Sachkontenlänge check is not applied to the missing account.
     */
    @Test
    fun `a null Gegenkonto is written as an empty field, everything else unchanged`() {
        val withAccount = DatevBuchungsstapelRowGenerator.generate(sampleInput(), sachkontenlaenge = 4)
        val without = DatevBuchungsstapelRowGenerator.generate(sampleInput(gegenkonto = null), sachkontenlaenge = 4)
        assertEquals(Cell.Int(8400), withAccount[7])
        assertEquals(Cell.Empty, without[7])
        assertEquals(withAccount.filterIndexed { i, _ -> i != 7 }, without.filterIndexed { i, _ -> i != 7 })

        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(without)
        val fields =
            writer
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trimEnd('\r', '\n')
                .split(";")
        assertEquals("1000", fields[6])
        assertEquals("", fields[7])
    }

    @Test
    fun `Belegdatum survives the writer as 8 characters`() {
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                BuchungsstapelRowInput(
                    umsatz = BigDecimal("19.00"),
                    belegDate = LocalDate.of(2026, 1, 2),
                    belegNr = "B1",
                    buchungstext = "Verkauf",
                    konto = 1000,
                    gegenkonto = 8400,
                ),
                sachkontenlaenge = 4,
            )
        val writer = DatevBuchungsstapelCsvWriter()
        writer.writeLine(cells)
        val field10 =
            writer
                .toByteArray()
                .toString(Charsets.ISO_8859_1)
                .trimEnd('\r', '\n')
                .split(";")[9]
        assertEquals("02012026", field10)
    }

    @Test
    fun `row has exactly 125 cells`() {
        val cells = DatevBuchungsstapelRowGenerator.generate(sampleInput(), sachkontenlaenge = 4)
        assertEquals(125, cells.size)
    }

    @Test
    fun `positive amount produces S Soll-Haben with abs value`() {
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                sampleInput(BigDecimal("100.00")),
                sachkontenlaenge = 4,
            )
        assertEquals(Cell.Amount(BigDecimal("100.00").abs()), cells[0])
        assertEquals(Cell.Str("S"), cells[1])
    }

    @Test
    fun `negative amount produces H Soll-Haben with positive Umsatz`() {
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                sampleInput(BigDecimal("-75.50")),
                sachkontenlaenge = 4,
            )
        assertEquals(Cell.Amount(BigDecimal("75.50")), cells[0])
        assertEquals(Cell.Str("H"), cells[1])
    }

    @Test
    fun `Konto and Gegenkonto are written as resolved, with Kasse in Konto`() {
        // The override/default pair is gone: the account mapping is the single source, so the
        // generator writes what it is given. Kasse must be in Konto — the Soll/Haben indicator is
        // applied to that side, so a contra account here inverts the whole booking.
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                sampleInput(gegenkonto = 8401),
                sachkontenlaenge = 4,
            )
        assertEquals(Cell.Int(1000), cells[6])
        assertEquals(Cell.Int(8401), cells[7])
    }

    @Test
    fun `Belegdatum uses TTMMJJJJ 8-digit format`() {
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                sampleInput(),
                sachkontenlaenge = 4,
            )
        assertEquals(Cell.Digits("21042026"), cells[9])
    }

    @Test
    fun `Belegfeld1 sanitized and truncated to 36 chars`() {
        val longBelegNr = "A;B\"C\r\nD".repeat(10) // sanitized length > 36
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                sampleInput(belegNr = longBelegNr),
                sachkontenlaenge = 4,
            )
        val value = (cells[10] as Cell.Str).value
        assertTrue(value.length <= 36)
        assertFalse(value.contains('"'))
        assertFalse(value.contains(';'))
    }

    @Test
    fun `zero Umsatz throws`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                DatevBuchungsstapelRowGenerator.generate(
                    sampleInput(umsatz = BigDecimal.ZERO),
                    sachkontenlaenge = 4,
                )
            }
        assertTrue(ex.message!!.contains("zero", ignoreCase = true))
    }

    @Test
    fun `Buchungstext truncated to 60 chars, sanitized`() {
        val longText = "A".repeat(70) + "; \"rest\""
        val cells =
            DatevBuchungsstapelRowGenerator.generate(
                sampleInput(buchungstext = longText),
                sachkontenlaenge = 4,
            )
        val text = (cells[13] as Cell.Str).value
        assertEquals(60, text.length)
        assertFalse(text.contains('"'))
        assertFalse(text.contains(';'))
    }

    @Test
    fun `Gegenkonto exceeding Sachkontenlaenge throws`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelRowGenerator.generate(
                sampleInput(gegenkonto = 123456), // > 4 digits
                sachkontenlaenge = 4,
            )
        }
    }

    @Test
    fun `unused columns are Cell Empty`() {
        val cells = DatevBuchungsstapelRowGenerator.generate(sampleInput(), sachkontenlaenge = 4)
        // Spot-check a handful of reserved indices
        assertEquals(Cell.Empty, cells[3]) // Kurs
        assertEquals(Cell.Empty, cells[4]) // Basis-Umsatz
        assertEquals(Cell.Empty, cells[12]) // Skonto
        assertEquals(Cell.Empty, cells[100]) // Herkunft-Kz etc
        assertEquals(Cell.Empty, cells[124]) // last column
    }

    @Test
    fun `Kost-Menge null emits AmountOrEmpty null`() {
        val cells = DatevBuchungsstapelRowGenerator.generate(sampleInput(), sachkontenlaenge = 4)
        assertEquals(Cell.AmountOrEmpty(null), cells[38])
    }
}
