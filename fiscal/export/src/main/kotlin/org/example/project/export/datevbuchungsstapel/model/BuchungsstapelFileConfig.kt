package org.example.project.export.datevbuchungsstapel.model

import java.time.LocalDate

/**
 * Inputs for the Buchungsstapel metadata header row (row 1 of
 * `EXTF_Buchungsstapel.csv`).
 *
 * Populated by the server from `DATEV_XML_CONFIG` plus the export request
 * parameters. See
 * `docs/superpowers/specs/2026-04-21-datev-buchungsstapel-export-design.md`
 * for the field-by-field mapping.
 */
data class BuchungsstapelFileConfig(
    val consultantNumber: String,
    val clientNumber: String,
    val wirtschaftsjahrBeginn: LocalDate,
    val sachkontenlaenge: Int, // 4..8
    val festschreibung: Boolean, // false → 0, true → 1
    val startDate: LocalDate,
    val endDate: LocalDate,
    val bezeichnung: String, // e.g., "Kassenbuch 2026-04-01–2026-04-30"
    val skr: String = "04", // v1: SKR04 only
)
