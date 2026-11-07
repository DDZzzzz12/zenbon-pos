package org.example.project.export.datevstammdaten

import org.example.project.export.datevstammdaten.DatevStammdatenCsvWriter.Cell
import org.example.project.export.datevstammdaten.model.StammdatenAccount

/**
 * Builds one 254-column Debitoren/Kreditoren data row from a [StammdatenAccount].
 *
 * **This generator places values; it does not judge them.** No truncation to DATEV's field
 * lengths, no digits-only check on the Postleitzahl, no IBAN or country-code validation. Those
 * rules belong to the resolver that turns a party into a [StammdatenAccount]: it is the layer with
 * the context to say *which* supplier lost *which* characters, and a silent trim here would hide
 * that from the operator.
 *
 * Columns are addressed by their documented 1-based DATEV number via [set]; the returned list is
 * 0-based, so the conversion happens in exactly one place.
 *
 * Source: https://developer.datev.de/de/file-format/details/datev-format/format-description/debitorskreditors
 */
object DatevStammdatenRowGenerator {
    fun build(account: StammdatenAccount): List<Cell> {
        val cells = MutableList<Cell>(DatevStammdatenColumns.TOTAL_COLUMNS) { Cell.Empty }

        // Konto is the only genuinely mandatory field, and it is unquoted digits: quoting it, or
        // routing it through an Int, would drop a leading zero and address a different account.
        cells.put(DatevStammdatenColumns.KONTO, Cell.Digits(account.konto))
        cells.put(DatevStammdatenColumns.NAME_UNTERNEHMEN, Cell.Str(account.name))
        // Quoted despite being a single digit — the format is per-field, not per-type.
        cells.put(DatevStammdatenColumns.ADRESSATENTYP, Cell.Str(DatevStammdatenColumns.ADRESSATENTYP_UNTERNEHMEN))
        cells.put(DatevStammdatenColumns.KURZBEZEICHNUNG, Cell.Str(account.kurzbezeichnung))

        cells.setText(DatevStammdatenColumns.EU_LAND, account.euLand)
        cells.setText(DatevStammdatenColumns.EU_UST_ID, account.euUstId)
        cells.setText(DatevStammdatenColumns.STRASSE, account.strasse)
        cells.setText(DatevStammdatenColumns.POSTLEITZAHL, account.postleitzahl)
        cells.setText(DatevStammdatenColumns.ORT, account.ort)
        cells.setText(DatevStammdatenColumns.LAND, account.land)
        cells.setText(DatevStammdatenColumns.STEUERNUMMER, account.steuernummer)

        // Bankleitzahl 1 (field 41) stays untouched on purpose: DATEV deletes the entire bank
        // record when it arrives as 0, and we have no BLZ to put there.
        cells.setText(DatevStammdatenColumns.BANKBEZEICHNUNG_1, account.bankName)
        cells.setText(DatevStammdatenColumns.IBAN_1, account.iban)
        cells.setText(DatevStammdatenColumns.SWIFT_1, account.bic)
        if (!account.iban.isNullOrBlank()) {
            // Flagging a main bank record that does not exist would be meaningless, and DATEV
            // allows the flag on at most one of the ten bank blocks.
            cells.put(DatevStammdatenColumns.KENNZ_HAUPTBANK_1, Cell.Str(DatevStammdatenColumns.HAUPTBANKVERBINDUNG))
        }

        // Unquoted digits, like Konto — the same company's other Personenkonto.
        account.verknuepftesOposKonto
            ?.takeIf { it.isNotBlank() }
            ?.let { cells.put(DatevStammdatenColumns.VERKNUEPFTES_OPOS_KONTO, Cell.Digits(it)) }

        return cells.toList()
    }

    /**
     * The single 1-based → 0-based conversion, so the constants read as DATEV documents them.
     *
     * Deliberately not named `set`: [MutableList] already has a member `set(index, element)`, a
     * member always wins over an extension, and the whole row then shifted one column — which is
     * exactly the silent defect the position tests exist to catch.
     */
    private fun MutableList<Cell>.put(
        datevPosition: Int,
        cell: Cell,
    ) {
        this[datevPosition - 1] = cell
    }

    /**
     * An absent optional text field is [Cell.Empty], never `Str("")`.
     *
     * Those are different bytes — `""` versus nothing between the separators — and for an optional
     * text field DATEV expects the absent form.
     */
    private fun MutableList<Cell>.setText(
        datevPosition: Int,
        value: String?,
    ) {
        if (!value.isNullOrBlank()) put(datevPosition, Cell.Str(value))
    }
}
