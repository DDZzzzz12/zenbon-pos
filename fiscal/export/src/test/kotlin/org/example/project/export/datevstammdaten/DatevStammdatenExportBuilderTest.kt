package org.example.project.export.datevstammdaten

import org.example.project.export.datevstammdaten.model.StammdatenAccount
import org.example.project.export.datevstammdaten.model.StammdatenFileConfig
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole file: metadata row, column-heading row, one row per account.
 *
 * Several assertions here split a line on `;`. That is only sound because **no field in these
 * fixtures contains a semicolon** — a `;` inside a quoted field would be counted as a separator
 * and the width assertions would then silently measure the wrong thing. Keep it that way when
 * adding a case.
 */
class DatevStammdatenExportBuilderTest {
    private val config =
        StammdatenFileConfig(
            consultantNumber = "29098",
            clientNumber = "55003",
            wirtschaftsjahrBeginn = LocalDate.of(2026, 1, 1),
            sachkontenlaenge = 4,
            bezeichnung = "Kunden und Lieferanten",
            skr = "03",
        )

    private val now = LocalDateTime.of(2026, 9, 2, 14, 6, 59)

    private val account =
        StammdatenAccount(
            konto = "70001",
            // An umlaut in the very field an operator is most likely to notice, so a broken
            // encoding shows up as a failing assertion rather than as mojibake in a support call.
            name = "Getränke-Müller GmbH",
            kurzbezeichnung = "Getraenke Mue",
            ort = "Köln",
        )

    /** Decoded the way DATEV reads the file. Never UTF-8 — that is the defect under test. */
    private fun render(accounts: List<StammdatenAccount>): String = DatevStammdatenExportBuilder.build(config, accounts, now).toString(Charsets.ISO_8859_1)

    /** The trailing CRLF makes `split` produce a final empty element; drop it. */
    private fun lines(text: String): List<String> = text.split("\r\n").dropLast(1)

    @Test
    fun `one account produces header, headings and one data line`() {
        val lines = lines(render(listOf(account)))

        assertEquals(3, lines.size, "metadata row + heading row + one data row")
    }

    @Test
    fun `the heading row carries 254 fields`() {
        // Catches a heading list that lost entries somewhere between the source and the constant.
        val headings = lines(render(listOf(account)))[1].split(";")

        assertEquals(254, headings.size)
        assertEquals("Konto", headings.first())
        assertEquals("Letzte Frist", headings.last())
    }

    @Test
    fun `a data row carries 254 fields, matching the heading row`() {
        val lines = lines(render(listOf(account)))

        assertEquals(254, lines[2].split(";").size)
        assertEquals(lines[1].split(";").size, lines[2].split(";").size)
    }

    @Test
    fun `the metadata row identifies the Debitoren slash Kreditoren format`() {
        val first = lines(render(listOf(account)))[0]

        assertTrue(
            first.startsWith("\"EXTF\";700;16;\"Debitoren/Kreditoren\";5;"),
            "unexpected metadata prefix: ${first.take(60)}",
        )
    }

    @Test
    fun `an umlaut survives the ISO-8859-1 round trip`() {
        val text = render(listOf(account))

        assertTrue(text.contains("\"Getränke-Müller GmbH\""), "supplier name was mangled")
        assertTrue(text.contains("\"Köln\""), "city was mangled")
        // The heading row has umlauts of its own, and they go through the same encoder.
        assertTrue(text.contains("Name (Adressatentyp natürl. Person)"), "heading row was mangled")
    }

    @Test
    fun `every line ends CRLF, including the last`() {
        val text = render(listOf(account, account.copy(konto = "70002")))

        assertTrue(text.endsWith("\r\n"), "the last line needs its terminator too")
        assertEquals(4, Regex("\r\n").findAll(text).count(), "one terminator per line")
    }

    @Test
    fun `an empty account list still produces header and headings`() {
        // A file with no accounts is a valid file; nothing here should special-case it.
        assertEquals(2, lines(render(emptyList())).size)
    }
}
