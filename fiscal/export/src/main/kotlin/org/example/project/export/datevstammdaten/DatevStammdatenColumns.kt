package org.example.project.export.datevstammdaten

/**
 * 1-based column positions in the 254-column Debitoren/Kreditoren row.
 *
 * Only the columns this export populates are named. The rest are emitted empty, which is why
 * [TOTAL_COLUMNS] is asserted by a test rather than implied by the constants below — a row of the
 * wrong width is rejected by DATEV without naming the field.
 *
 * Note the absence of a constant for field 41, `Bankleitzahl 1`: DATEV *deletes the whole bank
 * record* when that field is `0`, and we have no BLZ to write. Leaving it unnamed removes the
 * temptation to put a zero there.
 *
 * Source: https://developer.datev.de/de/file-format/details/datev-format/format-description/debitorskreditors
 */
object DatevStammdatenColumns {
    const val TOTAL_COLUMNS = 254

    const val KONTO = 1
    const val NAME_UNTERNEHMEN = 2
    const val ADRESSATENTYP = 7
    const val KURZBEZEICHNUNG = 8
    const val EU_LAND = 9
    const val EU_UST_ID = 10
    const val STRASSE = 16
    const val POSTLEITZAHL = 18
    const val ORT = 19
    const val LAND = 20
    const val BANKBEZEICHNUNG_1 = 42
    const val IBAN_1 = 45
    const val SWIFT_1 = 47
    const val KENNZ_HAUPTBANK_1 = 49
    const val STEUERNUMMER = 100
    const val VERKNUEPFTES_OPOS_KONTO = 232

    /** `2` = Unternehmen. `0` = keine Angabe, `1` = natürliche Person. */
    const val ADRESSATENTYP_UNTERNEHMEN = "2"

    /** `1` = this is the main bank record. At most one of the ten blocks may carry it. */
    const val HAUPTBANKVERBINDUNG = "1"
}
