package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelCsvWriter.Cell
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the 30-field metadata row (row 1) of an `EXTF_Buchungsstapel.csv`.
 *
 * See `docs/superpowers/specs/2026-04-21-datev-buchungsstapel-export-design.md`
 * — "Row 1 — metadata header" table — for field-by-field semantics. Column
 * indices 1-30 are 1-based per the spec; returned list is 0-based.
 */
object DatevBuchungsstapelMetadataHeader {
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")

    fun build(
        cfg: BuchungsstapelFileConfig,
        now: LocalDateTime,
    ): List<Cell> {
        DatevBuchungsstapelFieldValidator.validateConsultantNumber(cfg.consultantNumber)
        DatevBuchungsstapelFieldValidator.validateClientNumber(cfg.clientNumber)
        require(cfg.sachkontenlaenge in 4..8) {
            "sachkontenlaenge must be 4..8, got ${cfg.sachkontenlaenge}"
        }

        val wjBeginn = cfg.wirtschaftsjahrBeginn.format(DATE_FMT).toInt()
        val dateFrom = cfg.startDate.format(DATE_FMT).toInt()
        val dateTo = cfg.endDate.format(DATE_FMT).toInt()
        val timestamp = now.format(TS_FMT)

        return listOf(
            // 1
            Cell.Str("EXTF"),
            // 2
            Cell.Int(700),
            // 3
            Cell.Int(21),
            // 4
            Cell.Str("Buchungsstapel"),
            // 5
            Cell.Int(13),
            // 6 — Erzeugt am. Unquoted: DATEV's regex for this field is 17 bare digits with no
            // quote characters, and its own example header writes 20240130140440439 plainly.
            // Cell.Str would wrap it, which is what this export did until it was corrected.
            Cell.Digits(timestamp),
            // 7
            Cell.Empty,
            // 8
            Cell.Str("EX"),
            // 9
            Cell.Str(""),
            // 10
            Cell.Str(""),
            // 11
            Cell.Int(cfg.consultantNumber.toInt()),
            // 12
            Cell.Int(cfg.clientNumber.toInt()),
            // 13
            Cell.Int(wjBeginn),
            // 14
            Cell.Int(cfg.sachkontenlaenge),
            // 15
            Cell.Int(dateFrom),
            // 16
            Cell.Int(dateTo),
            // 17
            Cell.Str(cfg.bezeichnung),
            // 18
            Cell.Str("EX"),
            // 19
            Cell.Int(1),
            // 20
            Cell.Int(0),
            // 21
            Cell.Int(if (cfg.festschreibung) 1 else 0),
            // 22
            Cell.Str("EUR"),
            // 23
            Cell.Empty,
            // 24
            Cell.Str(""),
            // 25
            Cell.Empty,
            // 26
            Cell.Empty,
            // 27
            Cell.Str(cfg.skr),
            // 28
            Cell.Empty,
            // 29
            Cell.Empty,
            // 30
            Cell.Str(""),
            // 31 — Anwendungsinformation, `^["].{0,16}["]$`. Quoted-empty, matching DATEV's own
            // Buchungsstapel example, which ends `;"03";;;"";""`. This export emitted only 30
            // fields until it was corrected against the format description.
            Cell.Str(""),
        )
    }
}
