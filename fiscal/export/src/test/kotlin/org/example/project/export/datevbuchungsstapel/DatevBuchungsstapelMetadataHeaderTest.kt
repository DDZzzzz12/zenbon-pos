package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelCsvWriter.Cell
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatevBuchungsstapelMetadataHeaderTest {
    private fun sampleConfig(
        consultantNumber: String = "29098",
        clientNumber: String = "55003",
        sachkontenlaenge: Int = 4,
        festschreibung: Boolean = false,
    ) = BuchungsstapelFileConfig(
        consultantNumber = consultantNumber,
        clientNumber = clientNumber,
        wirtschaftsjahrBeginn = LocalDate.of(2026, 1, 1),
        sachkontenlaenge = sachkontenlaenge,
        festschreibung = festschreibung,
        startDate = LocalDate.of(2026, 4, 1),
        endDate = LocalDate.of(2026, 4, 30),
        bezeichnung = "Kassenbuch 2026-04",
    )

    private val fixedNow = LocalDateTime.of(2026, 4, 21, 14, 30, 12, 345_000_000)

    @Test
    fun `metadata row has exactly 30 cells`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(), now = fixedNow)
        assertEquals(31, cells.size)
    }

    @Test
    fun `first five cells identify EXTF 700 Buchungsstapel formatversion 13`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(), now = fixedNow)
        assertEquals(Cell.Str("EXTF"), cells[0])
        assertEquals(Cell.Int(700), cells[1])
        assertEquals(Cell.Int(21), cells[2])
        assertEquals(Cell.Str("Buchungsstapel"), cells[3])
        assertEquals(Cell.Int(13), cells[4])
    }

    @Test
    fun `created-at timestamp is yyyyMMddHHmmssSSS`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(), now = fixedNow)
        assertEquals(Cell.Digits("20260421143012345"), cells[5])
    }

    @Test
    fun `consultant and client numbers are int cells`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(), now = fixedNow)
        assertEquals(Cell.Int(29098), cells[10])
        assertEquals(Cell.Int(55003), cells[11])
    }

    @Test
    fun `WJ-Beginn and date range are yyyyMMdd integers`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(), now = fixedNow)
        assertEquals(Cell.Int(20260101), cells[12]) // WJ-Beginn
        assertEquals(Cell.Int(20260401), cells[14]) // Datum-von
        assertEquals(Cell.Int(20260430), cells[15]) // Datum-bis
    }

    @Test
    fun `festschreibung false emits zero`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(festschreibung = false), now = fixedNow)
        assertEquals(Cell.Int(0), cells[20])
    }

    @Test
    fun `festschreibung true emits one`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(festschreibung = true), now = fixedNow)
        assertEquals(Cell.Int(1), cells[20])
    }

    @Test
    fun `currency cell is EUR`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(), now = fixedNow)
        assertEquals(Cell.Str("EUR"), cells[21])
    }

    @Test
    fun `SKR variant defaults to 04`() {
        val cells = DatevBuchungsstapelMetadataHeader.build(sampleConfig(), now = fixedNow)
        assertEquals(Cell.Str("04"), cells[26])
    }

    @Test
    fun `rejects sachkontenlaenge outside 4 to 8`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelMetadataHeader.build(sampleConfig(sachkontenlaenge = 3), now = fixedNow)
        }
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelMetadataHeader.build(sampleConfig(sachkontenlaenge = 9), now = fixedNow)
        }
    }

    @Test
    fun `rejects non-digit consultant number`() {
        assertFailsWith<IllegalArgumentException> {
            DatevBuchungsstapelMetadataHeader.build(
                sampleConfig(consultantNumber = "29098A"),
                now = fixedNow,
            )
        }
    }
}
