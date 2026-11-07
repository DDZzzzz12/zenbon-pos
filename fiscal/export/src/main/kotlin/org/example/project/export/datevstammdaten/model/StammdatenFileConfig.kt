package org.example.project.export.datevstammdaten.model

import java.time.LocalDate

/**
 * What the DATEV header needs that the data cannot supply.
 *
 * Mirrors `BuchungsstapelFileConfig`, minus the period: master data has no date range, so header
 * fields 15 and 16 stay empty.
 */
data class StammdatenFileConfig(
    val consultantNumber: String,
    val clientNumber: String,
    val wirtschaftsjahrBeginn: LocalDate,
    /** 4..8. Personenkontenlänge is this plus one. */
    val sachkontenlaenge: Int,
    /** Free-text label for the batch, max 30 characters. */
    val bezeichnung: String,
    /** `"03"` or `"04"`. */
    val skr: String,
)
