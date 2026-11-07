package org.example.project.export.datevstammdaten

import org.example.project.export.datevstammdaten.model.StammdatenAccount
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One account becomes one 254-column row.
 *
 * Positions are asserted literally against DATEV's documented layout. A field written one column
 * out is not rejected — it lands in a *different, valid* field, so the file imports and the data is
 * silently wrong. That is why these are pinned rather than checked in aggregate.
 */
class DatevStammdatenRowGeneratorTest {
    private val account =
        StammdatenAccount(
            konto = "70001",
            name = "Getränke-Müller GmbH",
            kurzbezeichnung = "Getränke-Mülle",
            euLand = "DE",
            euUstId = "222333444",
            strasse = "Kelterweg 8",
            postleitzahl = "70182",
            ort = "Stuttgart",
            land = "DE",
            iban = "DE21500500009876543210",
            verknuepftesOposKonto = "10001",
        )

    @Test
    fun `the row is exactly 254 columns`() {
        assertEquals(DatevStammdatenColumns.TOTAL_COLUMNS, DatevStammdatenRowGenerator.build(account).size)
    }

    @Test
    fun `the populated columns land in their documented positions`() {
        val row = DatevStammdatenRowGenerator.build(account)

        // 1-based DATEV positions, 0-based list.
        assertEquals(DatevStammdatenCsvWriter.Cell.Digits("70001"), row[0])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("Getränke-Müller GmbH"), row[1])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("2"), row[6], "Adressatentyp - quoted despite being a digit")
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("DE"), row[8])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("222333444"), row[9], "VAT id without its country prefix")
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("Kelterweg 8"), row[15])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("70182"), row[17])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("Stuttgart"), row[18])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("DE21500500009876543210"), row[44])
        assertEquals(DatevStammdatenCsvWriter.Cell.Str("1"), row[48], "Kennz. Haupt-Bankverb. 1")
        assertEquals(DatevStammdatenCsvWriter.Cell.Digits("10001"), row[231], "Verknüpftes OPOS-Konto - unquoted")
    }

    /** Every column we do not populate must still be present, and empty. */
    @Test
    fun `unpopulated columns are empty, not absent`() {
        val row = DatevStammdatenRowGenerator.build(account)

        assertEquals(
            DatevStammdatenCsvWriter.Cell.Empty,
            row[40],
            "Bankleitzahl 1 - never written; DATEV deletes the bank record when it is 0",
        )
        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, row[253], "Letzte Frist - the last column")
    }

    @Test
    fun `an account with no linked counterpart leaves column 232 empty`() {
        val row = DatevStammdatenRowGenerator.build(account.copy(verknuepftesOposKonto = null))

        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, row[231])
    }

    /** Flagging a main bank record that does not exist would be meaningless. */
    @Test
    fun `an account with no IBAN does not flag a main bank record`() {
        val row = DatevStammdatenRowGenerator.build(account.copy(iban = null))

        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, row[44], "IBAN 1")
        assertEquals(DatevStammdatenCsvWriter.Cell.Empty, row[48], "Kennz. Haupt-Bankverb. 1")
    }
}
