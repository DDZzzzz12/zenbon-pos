package org.example.project.features.kassenbuch.data.service

import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tells the operator when a Buchungsstapel carries Beleg dates its requested period does not cover.
 *
 * Entries are selected for an export by their booking time — `created_at`, the TSE-attested moment —
 * while each row keeps the date printed on the receipt. Since a late receipt books into the open
 * period rather than its own (`e1240a3c2`), and the backdating override reaches up to a year back
 * (`7d9eed53c`), a row's Belegdatum can precede the exported period, and with the override it can
 * fall in the previous Wirtschaftsjahr.
 *
 * The file itself stays self-consistent — `DatevBuchungsstapelExportBuilder` widens `Datum vom`/
 * `Datum bis` to cover the rows — but widening cannot make one batch belong to two Wirtschaftsjahre,
 * and that is a booking decision, not something this export may take on the operator's behalf.
 *
 * Informational only, exactly like [SplitTransferWarnings]: it never fails the export. What DATEV's
 * import does with a previous-year Beleg date in a current-year batch is not asserted here — the
 * warning names the situation and leaves the judgement to the operator and their accountant.
 */
object BuchungsstapelPeriodWarnings {
    private val GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * @param rows The mapped rows about to be written.
     * @param requestedStart First day of the period the operator asked for.
     * @param requestedEnd Last day of that period.
     * @param wirtschaftsjahrBeginn The configured start of the fiscal year; only its month and day
     *        matter, so any year works as the anchor.
     * @return Zero to two warnings: one when rows fall outside the requested period, one when they
     *         do not all belong to the same Wirtschaftsjahr.
     */
    fun detect(
        rows: List<BuchungsstapelRowInput>,
        requestedStart: LocalDate,
        requestedEnd: LocalDate,
        wirtschaftsjahrBeginn: LocalDate,
    ): List<String> {
        val warnings = mutableListOf<String>()

        val outside = rows.map { it.belegDate }.filter { it < requestedStart || it > requestedEnd }
        if (outside.isNotEmpty()) {
            warnings +=
                "${outside.size} booking(s) carry a Beleg date outside the exported period " +
                "(earliest ${outside.min().format(GERMAN_DATE)}). A receipt handed in late keeps its own " +
                "Beleg date and books into the open period, so the file's period was widened to cover them."
        }

        val fiscalYears = rows.map { fiscalYearStart(it.belegDate, wirtschaftsjahrBeginn) }.distinct().sorted()
        if (fiscalYears.size > 1) {
            warnings +=
                "This Buchungsstapel spans ${fiscalYears.size} Wirtschaftsjahre " +
                "(${fiscalYears.joinToString(", ") { it.format(GERMAN_DATE) }}). A batch is normally posted " +
                "into one Wirtschaftsjahr — export the affected receipts separately, or agree with your " +
                "accountant how a receipt from a closed year is to be booked."
        }

        return warnings
    }

    /**
     * The first day of the Wirtschaftsjahr [date] falls in, for a fiscal year that recurs on
     * [wirtschaftsjahrBeginn]'s month and day. A year beginning on 1 July puts 30 June in the
     * Wirtschaftsjahr that started the previous 1 July.
     */
    private fun fiscalYearStart(
        date: LocalDate,
        wirtschaftsjahrBeginn: LocalDate,
    ): LocalDate {
        val startInSameYear = date.withMonth(wirtschaftsjahrBeginn.monthValue).withDayOfMonth(wirtschaftsjahrBeginn.dayOfMonth)
        return if (date < startInSameYear) startInSameYear.minusYears(1) else startInSameYear
    }
}
