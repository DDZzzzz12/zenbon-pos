package org.example.project.export.datevstammdaten

import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelFieldValidator
import org.example.project.export.datevstammdaten.DatevStammdatenCsvWriter.Cell
import org.example.project.export.datevstammdaten.model.StammdatenFileConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the **31**-field metadata row (row 1) of the Debitoren-/Kreditorenstamm CSV.
 *
 * Verified against DATEV's documented example for this format (developer portal, header
 * description, September 2026):
 *
 * ```
 * "EXTF";700;16;"Debitoren/Kreditoren";5;20240130140659583;;"RE";"";"";29098;55003;20240101;4;;;"";"";;;;"";;"";;;"03";;;"";""
 * ```
 *
 * **This is not the Buchungsstapel header with different constants.** It is one field longer (31
 * vs. 30), and four of the fields the Buchungsstapel fills are empty here because they describe
 * *bookings*, which master data does not contain: 15/16 (the period), 19 (Buchungstyp), 20
 * (Rechnungslegungszweck), 21 (Festschreibung). Copying the Buchungsstapel row and swapping the
 * format name is the single likeliest way to produce a file DATEV rejects — and it rejects the
 * whole file, with an error that does not name the offending field.
 *
 * Field numbers in the comments below are 1-based, as DATEV numbers them; the returned list is
 * 0-based.
 */
object DatevStammdatenMetadataHeader {
    /** Field 13, WJ-Beginn. */
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Field 6, Erzeugt am — to the millisecond, and unquoted. */
    private val TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")

    /** Field 17, Bezeichnung. */
    private const val MAX_BEZEICHNUNG = 30

    fun build(
        cfg: StammdatenFileConfig,
        now: LocalDateTime,
    ): List<Cell> {
        // Beraternummer and Mandantennummer have the same digit-only contract in both formats, so
        // the Buchungsstapel validator is reused rather than duplicated — one place to fix.
        DatevBuchungsstapelFieldValidator.validateConsultantNumber(cfg.consultantNumber)
        DatevBuchungsstapelFieldValidator.validateClientNumber(cfg.clientNumber)
        require(cfg.sachkontenlaenge in 4..8) {
            "sachkontenlaenge must be 4..8, got ${cfg.sachkontenlaenge}"
        }
        require(cfg.skr == "03" || cfg.skr == "04") {
            "Sachkontenrahmen must be \"03\" or \"04\", got '${cfg.skr}'"
        }

        val wjBeginn = cfg.wirtschaftsjahrBeginn.format(DATE_FMT).toInt()

        return listOf(
            // 1 Kennzeichen — always the external format marker.
            Cell.Str("EXTF"),
            // 2 Versionsnummer — 700 is the only value DATEV accepts.
            Cell.Int(700),
            // 3 Formatkategorie — 16 = Debitoren-/Kreditorenstamm (21 is Buchungsstapel).
            Cell.Int(16),
            // 4 Formatname.
            Cell.Str("Debitoren/Kreditoren"),
            // 5 Formatversion.
            Cell.Int(5),
            // 6 Erzeugt am — unquoted, so it goes through Digits and not Str.
            Cell.Digits(now.format(TS_FMT)),
            // 7 Importiert — set by DATEV on import, never by us.
            Cell.Empty,
            // 8 Herkunft — the originating system's two-character marker. DATEV's example shows
            // "RE"; we send the same "EX" the Buchungsstapel sends, because it identifies *us*.
            // DATEV overwrites this on import in any case.
            Cell.Str("EX"),
            // 9 Exportiert von.
            Cell.Str(""),
            // 10 Importiert von.
            Cell.Str(""),
            // 11 Beraternummer.
            Cell.Int(cfg.consultantNumber.toInt()),
            // 12 Mandantennummer.
            Cell.Int(cfg.clientNumber.toInt()),
            // 13 WJ-Beginn — still required for master data, even without a period.
            Cell.Int(wjBeginn),
            // 14 Sachkontenlänge — Personenkontenlänge is this plus one.
            Cell.Int(cfg.sachkontenlaenge),
            // 15 Datum von — EMPTY. Master data has no period.
            Cell.Empty,
            // 16 Datum bis — EMPTY, same reason.
            Cell.Empty,
            // 17 Bezeichnung — truncated rather than rejected: a label too long must not fail an
            // otherwise valid export, and the text is descriptive only.
            Cell.Str(DatevBuchungsstapelFieldValidator.sanitizeAndTruncate(cfg.bezeichnung, MAX_BEZEICHNUNG)),
            // 18 Diktatkürzel.
            Cell.Str(""),
            // 19 Buchungstyp — EMPTY, master data holds no bookings.
            Cell.Empty,
            // 20 Rechnungslegungszweck — EMPTY, same reason.
            Cell.Empty,
            // 21 Festschreibung — EMPTY, same reason.
            Cell.Empty,
            // 22 WKZ — EMPTY of content; DATEV's example writes it as a quoted empty field.
            Cell.Str(""),
            // 23 reserved.
            Cell.Empty,
            // 24 Derivatskennzeichen.
            Cell.Str(""),
            // 25 reserved.
            Cell.Empty,
            // 26 reserved.
            Cell.Empty,
            // 27 Sachkontenrahmen — "03" or "04", quoted.
            Cell.Str(cfg.skr),
            // 28 ID der Branchenlösung.
            Cell.Empty,
            // 29 reserved.
            Cell.Empty,
            // 30 reserved.
            Cell.Str(""),
            // 31 Anwendungsinformation — the field the Buchungsstapel header does not have.
            Cell.Str(""),
        )
    }
}
