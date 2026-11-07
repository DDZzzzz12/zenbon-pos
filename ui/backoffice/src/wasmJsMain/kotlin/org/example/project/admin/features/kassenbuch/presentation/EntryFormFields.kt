package org.example.project.admin.features.kassenbuch.presentation

/**
 * Focus/error keys of the "+ Buchung" form. Strings rather than an enum because the Beleg lines are a
 * list: each line's amount and VAT rate is its own field, keyed by its index.
 */
object EntryFormFields {
    const val BELEG_DATUM = "belegDatum"
    const val LATE_REASON = "lateBookingReason"
    const val BELEGTEXT = "belegtext"

    fun amount(line: Int): String = "line$line.amount"

    fun vatRate(line: Int): String = "line$line.vatRate"
}
