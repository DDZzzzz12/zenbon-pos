package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelCsvWriter.Cell
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/**
 * Builds a single 125-column Buchungsstapel data row from input primitives.
 *
 * Column indices in the returned list are 0-based; the corresponding DATEV
 * field numbers are 1-based. Only columns the v1 export populates carry
 * non-empty cells — everything else is [Cell.Empty].
 *
 * See `docs/superpowers/specs/2026-04-21-datev-buchungsstapel-export-design.md`
 * for the populated-column table.
 */
object DatevBuchungsstapelRowGenerator {
    private const val TOTAL_COLUMNS = 125
    private val BELEGDATE_FMT = DateTimeFormatter.ofPattern("ddMMyyyy")

    fun generate(
        input: BuchungsstapelRowInput,
        sachkontenlaenge: Int,
    ): List<Cell> {
        require(input.umsatz.compareTo(BigDecimal.ZERO) != 0) {
            "Umsatz must be nonzero (DATEV rejects zero-amount rows)"
        }

        val konto = input.konto
        val gegenkonto = input.gegenkonto
        DatevBuchungsstapelFieldValidator.validateAccountNumber(konto, sachkontenlaenge, "Konto")
        gegenkonto?.let { DatevBuchungsstapelFieldValidator.validateAccountNumber(it, sachkontenlaenge, "Gegenkonto") }

        val umsatzAbs = input.umsatz.abs()
        val sh = if (input.umsatz.signum() >= 0) "S" else "H"

        // Formatted string, NOT an Int: "02012026".toInt() is 2012026, and the writer
        // appends an Int unpadded, so every day 1..9 shipped a 7-digit Belegdatum
        // (~30 % of all dates). Field 10 is Length 8 / FormatType Datum in
        // Format_Buchungsstapel.xml — 8-digit TTMMJJJJ is within spec; the neighbouring
        // field declares Length 4, so 8 here is deliberate. DATEV's own Musterdaten uses
        // the 4-digit TTMM form, which is also valid but drops the year — and the export
        // selects rows by createdAt, not belegDatum, so a Belegdatum can legitimately sit
        // outside the file's own period and the year is worth keeping.
        val belegdatum = input.belegDate.format(BELEGDATE_FMT)

        val belegfeld1 =
            DatevBuchungsstapelFieldValidator.sanitizeAndTruncate(
                input.belegNr,
                DatevBuchungsstapelFieldValidator.MAX_BELEGFELD,
            )
        val buchungstext =
            DatevBuchungsstapelFieldValidator.sanitizeAndTruncate(
                input.buchungstext,
                DatevBuchungsstapelFieldValidator.MAX_BUCHUNGSTEXT,
            )
        val kost1 =
            input.kost1?.let {
                DatevBuchungsstapelFieldValidator.sanitizeAndTruncate(it, DatevBuchungsstapelFieldValidator.MAX_KOST)
            }
        val kost2 =
            input.kost2?.let {
                DatevBuchungsstapelFieldValidator.sanitizeAndTruncate(it, DatevBuchungsstapelFieldValidator.MAX_KOST)
            }

        val cells = Array<Cell>(TOTAL_COLUMNS) { Cell.Empty }
        cells[0] = Cell.Amount(umsatzAbs) // Umsatz (pos)
        cells[1] = Cell.Str(sh) // Soll/Haben-Kz
        cells[2] = Cell.Str("") // WKZ Umsatz (inherit from header)
        cells[5] = Cell.Str("") // WKZ Basis-Umsatz (matches sample)
        cells[6] = Cell.Int(konto) // Konto
        // Empty, never `0`, when the account is left to DATEV (see BuchungsstapelRowInput.gegenkonto);
        // a present account is the same Cell.Int as always, so an all-ON mapping is byte-identical.
        cells[7] = gegenkonto?.let { Cell.Int(it) } ?: Cell.Empty // Gegenkonto
        cells[8] = Cell.Str(input.bu ?: "") // BU-Schlüssel
        cells[9] = Cell.Digits(belegdatum) // Belegdatum TTMMJJJJ
        cells[10] = Cell.Str(belegfeld1) // Belegfeld 1
        cells[11] = Cell.Str("") // Belegfeld 2
        cells[13] = Cell.Str(buchungstext) // Buchungstext
        cells[19] = input.belegLink?.let { Cell.Str(it) } ?: Cell.Empty // Beleglink (col 20)
        cells[36] = Cell.Str(kost1 ?: "") // KOST1
        cells[37] = Cell.Str(kost2 ?: "") // KOST2
        cells[38] = Cell.AmountOrEmpty(input.kostMenge) // Kost-Menge

        return cells.toList()
    }
}
