package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import java.time.LocalDateTime

/**
 * Orchestrates construction of an `EXTF_Buchungsstapel.csv` from a list of
 * pre-mapped [BuchungsstapelRowInput] instances.
 *
 * Delegates:
 * - row 1 (metadata): [DatevBuchungsstapelMetadataHeader]
 * - row 2 (column names): hardcoded verbatim, see [COLUMN_NAMES_ROW]
 * - rows 3..N (data): [DatevBuchungsstapelRowGenerator]
 * - byte-level formatting: [DatevBuchungsstapelCsvWriter]
 */
object DatevBuchungsstapelExportBuilder {
    /**
     * Verbatim column-names row of the DATEV EXTF Buchungsstapel format version 700.
     *
     * 125 semicolon-separated field names. Written as-is by
     * [DatevBuchungsstapelCsvWriter.appendRaw] — DATEV accepts this row unquoted.
     *
     * Eight of the names carry umlauts (`BU-Schlüssel`, `Geschäftspartnerbank`,
     * `Funktionsergänzung`, `Stück`, `Fälligkeit`, `Erlöskonto`, …), which is why
     * the file's encoding is not a question that only arises for German booking
     * text: it decides whether the header row itself is readable.
     */
    const val COLUMN_NAMES_ROW =
        "Umsatz (ohne Soll/Haben-Kz);Soll/Haben-Kennzeichen;WKZ Umsatz;Kurs;Basis-Umsatz;WKZ Basis-Umsatz;Konto;Gegenkonto (ohne BU-Schlüssel);BU-Schlüssel;Belegdatum;Belegfeld 1;Belegfeld 2;Skonto;Buchungstext;Postensperre;Diverse Adressnummer;Geschäftspartnerbank;Sachverhalt;Zinssperre;Beleglink;Beleginfo - Art 1;Beleginfo - Inhalt 1;Beleginfo - Art 2;Beleginfo - Inhalt 2;Beleginfo - Art 3;Beleginfo - Inhalt 3;Beleginfo - Art 4;Beleginfo - Inhalt 4;Beleginfo - Art 5;Beleginfo - Inhalt 5;Beleginfo - Art 6;Beleginfo - Inhalt 6;Beleginfo - Art 7;Beleginfo - Inhalt 7;Beleginfo - Art 8;Beleginfo - Inhalt 8;KOST1 - Kostenstelle;KOST2 - Kostenstelle;Kost-Menge;EU-Land u. UStID (Bestimmung);EU-Steuersatz (Bestimmung);Abw. Versteuerungsart;Sachverhalt L+L;Funktionsergänzung L+L;BU 49 Hauptfunktionstyp;BU 49 Hauptfunktionsnummer;BU 49 Funktionsergänzung;Zusatzinformation - Art 1;Zusatzinformation- Inhalt 1;Zusatzinformation - Art 2;Zusatzinformation- Inhalt 2;Zusatzinformation - Art 3;Zusatzinformation- Inhalt 3;Zusatzinformation - Art 4;Zusatzinformation- Inhalt 4;Zusatzinformation - Art 5;Zusatzinformation- Inhalt 5;Zusatzinformation - Art 6;Zusatzinformation- Inhalt 6;Zusatzinformation - Art 7;Zusatzinformation- Inhalt 7;Zusatzinformation - Art 8;Zusatzinformation- Inhalt 8;Zusatzinformation - Art 9;Zusatzinformation- Inhalt 9;Zusatzinformation - Art 10;Zusatzinformation- Inhalt 10;Zusatzinformation - Art 11;Zusatzinformation- Inhalt 11;Zusatzinformation - Art 12;Zusatzinformation- Inhalt 12;Zusatzinformation - Art 13;Zusatzinformation- Inhalt 13;Zusatzinformation - Art 14;Zusatzinformation- Inhalt 14;Zusatzinformation - Art 15;Zusatzinformation- Inhalt 15;Zusatzinformation - Art 16;Zusatzinformation- Inhalt 16;Zusatzinformation - Art 17;Zusatzinformation- Inhalt 17;Zusatzinformation - Art 18;Zusatzinformation- Inhalt 18;Zusatzinformation - Art 19;Zusatzinformation- Inhalt 19;Zusatzinformation - Art 20;Zusatzinformation- Inhalt 20;Stück;Gewicht;Zahlweise;Forderungsart;Veranlagungsjahr;Zugeordnete Fälligkeit;Skontotyp;Auftragsnummer;Buchungstyp;USt-Schlüssel (Anzahlungen);EU-Land (Anzahlungen);Sachverhalt L+L (Anzahlungen);EU-Steuersatz (Anzahlungen);Erlöskonto (Anzahlungen);Herkunft-Kz;Buchungs GUID;KOST-Datum;SEPA-Mandatsreferenz;Skontosperre;Gesellschaftername;Beteiligtennummer;Identifikationsnummer;Zeichnernummer;Postensperre bis;Bezeichnung SoBil-Sachverhalt;Kennzeichen SoBil-Buchung;Festschreibung;Leistungsdatum;Datum Zuord. Steuerperiode;Fälligkeit;Generalumkehr (GU);Steuersatz;Land;Abrechnungsreferenz;BVV-Position;EU-Land u. UStID (Ursprung);EU-Steuersatz (Ursprung);Abw. Skontokonto"

    fun build(
        cfg: BuchungsstapelFileConfig,
        rows: List<BuchungsstapelRowInput>,
        now: LocalDateTime = LocalDateTime.now(),
    ): ByteArray {
        val writer = DatevBuchungsstapelCsvWriter()
        // Row 1: metadata (30 cells)
        writer.writeLine(DatevBuchungsstapelMetadataHeader.build(cfg.coveringRows(rows), now))
        // Row 2: column names (pre-joined, 125 fields)
        writer.appendRaw(COLUMN_NAMES_ROW)
        writer.appendLineTerminator()
        // Rows 3..N: data (125 cells each)
        rows.forEach { row ->
            writer.writeLine(DatevBuchungsstapelRowGenerator.generate(row, cfg.sachkontenlaenge))
        }
        return writer.toByteArray()
    }

    /**
     * The config with `Datum vom`/`Datum bis` widened to cover every row's Belegdatum.
     *
     * The header states the period of the batch, so it must contain the rows the batch carries. A
     * receipt handed in after a close keeps its own Beleg date while booking into the open period
     * (`e1240a3c2`), so a row can precede the requested range — and the file then contradicted
     * itself (E2E-2026-09-22 R3-1). Widening, rather than replacing, keeps the requested range
     * visible whenever the rows fall inside it, which is every ordinary export.
     *
     * Symmetric on purpose, though only the lower bound moves in practice: a Beleg date may not
     * lie in the future, so it cannot exceed the booking date the selection is made on.
     */
    private fun BuchungsstapelFileConfig.coveringRows(rows: List<BuchungsstapelRowInput>): BuchungsstapelFileConfig {
        val belegDates = rows.map { it.belegDate }
        val earliest = (belegDates + startDate).min()
        val latest = (belegDates + endDate).max()
        return if (earliest == startDate && latest == endDate) this else copy(startDate = earliest, endDate = latest)
    }
}
