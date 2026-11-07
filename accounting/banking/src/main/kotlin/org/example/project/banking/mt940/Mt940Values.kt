package org.example.project.banking.mt940

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * The scalar conversions MT940 needs. Each one is a documented bug class rather than a formality.
 */
object Mt940Values {
    /**
     * MT940 writes amounts with a **comma** decimal separator and no thousands grouping. Two naive
     * readings both fail silently: `"1234,56".toBigDecimal()` throws, and stripping the comma yields
     * `123456` — a hundredfold error that still looks like money. A trailing comma with no decimals
     * ("500,") is legal and means exactly 500.
     */
    fun amount(raw: String): BigDecimal {
        val normalised = raw.trim().replace(',', '.').removeSuffix(".")
        return runCatching { BigDecimal(normalised) }
            .getOrElse { throw Mt940ParseException("cannot read an amount from \"$raw\"", it) }
    }

    /** `YYMMDD`, mapped into 2000–2099. MT940 predates the concern and offers no century. */
    fun date(raw: String): LocalDate {
        if (raw.length != 6 || !raw.all { it.isDigit() }) {
            throw Mt940ParseException("cannot read a YYMMDD date from \"$raw\"")
        }
        return runCatching {
            LocalDate(2000 + raw.substring(0, 2).toInt(), raw.substring(2, 4).toInt(), raw.substring(4, 6).toInt())
        }.getOrElse { throw Mt940ParseException("cannot read a YYMMDD date from \"$raw\"", it) }
    }

    /**
     * The `:61:` booking date is `MMDD` with **no year**, so it is inferred from the value date.
     *
     * The year-boundary case is the one that breaks naive implementations: an entry booked on
     * 31 December with a value date of 3 January belongs to the *previous* year. Taking the value
     * date's year unconditionally would file it eleven months in the future, and the statement would
     * still balance — the error is invisible to every arithmetic check.
     *
     * Rule: start from the value date's year; if that lands more than 30 days *after* the value
     * date, step back a year; if more than 335 days *before*, step forward.
     */
    fun bookingDate(
        raw: String?,
        valueDate: LocalDate,
    ): LocalDate {
        if (raw == null) return valueDate
        if (raw.length != 4 || !raw.all { it.isDigit() }) {
            throw Mt940ParseException("cannot read an MMDD booking date from \"$raw\"")
        }
        val month = raw.substring(0, 2).toInt()
        val dayOfMonth = raw.substring(2, 4).toInt()

        val candidate = safeDate(valueDate.year, month, dayOfMonth, raw)
        val delta = candidate.toEpochDays() - valueDate.toEpochDays()
        return when {
            delta > 30 -> safeDate(valueDate.year - 1, month, dayOfMonth, raw)
            delta < -335 -> safeDate(valueDate.year + 1, month, dayOfMonth, raw)
            else -> candidate
        }
    }

    /**
     * 29 February is the trap: a booking date valid in the value date's year may be invalid in the
     * adjusted one. Falling back to the 28th keeps a real entry rather than failing the statement
     * over a calendar edge.
     */
    private fun safeDate(
        year: Int,
        month: Int,
        dayOfMonth: Int,
        raw: String,
    ): LocalDate =
        runCatching { LocalDate(year, month, dayOfMonth) }
            .recoverCatching { LocalDate(year, month, dayOfMonth - 1) }
            .getOrElse { throw Mt940ParseException("cannot build a booking date from \"$raw\" in $year", it) }
}
