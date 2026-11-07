package org.example.project.core.utils

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Which Beleg dates a new Kassenbuch entry may carry — the one definition the server guard and both
 * clients' entry forms apply.
 *
 * The Beleg date is the date printed on the receipt. It does NOT decide the period: an entry belongs
 * to the period its booking time (`created_at`, the TSE-attested moment) falls in, which is how the
 * Z-Bon sweep, the Kassensturz, DSFinV-K and the DATEV exports already select entries. A receipt
 * handed in after a Z-Bon or Kassensturz therefore keeps its real date and books into the open
 * period. The old rule refused any date on or before the last close, so a receipt from yesterday —
 * or an expense on the day of a mid-day Z-Bon — could not be booked at all (E2E-2026-09-20 round 2).
 *
 * What stays refused: a future date, and a date further back than the shop's limit
 * (`kassenbuch_max_beleg_age_days`, default [DEFAULT_MAX_AGE_DAYS]). The limit replaces the old
 * protection against a mistyped `01.01.1999`.
 */
object KassenbuchBelegDateRule {
    /** One month: a late receipt is normal, one older than that is not. */
    const val DEFAULT_MAX_AGE_DAYS = 31

    /** The range an operator may configure; a year is the outer bound of any cash book period. */
    val ALLOWED_MAX_AGE_DAYS = 1..366

    /**
     * The backdating override's hard ceiling: even with a reason and a manager's authority, a Beleg
     * date may not lie more than a year back — that is a mistyped year, not a late receipt.
     */
    const val OVERRIDE_CEILING_DAYS = 366

    /** The longest late-booking reason the entry stores (`kassenbuch_header.late_booking_reason`). */
    const val MAX_LATE_BOOKING_REASON_LENGTH = 500

    /**
     * True when [belegDate] is past the shop's limit but within [OVERRIDE_CEILING_DAYS]: the case the
     * backdating override (reason + `CASHBOOK_BACKDATE` or a manager's approval) exists for.
     */
    fun needsOverride(
        belegDate: LocalDate,
        today: LocalDate,
        maxAgeDays: Int,
    ): Boolean =
        check(belegDate, today, maxAgeDays) == KassenbuchBelegDateViolation.TOO_OLD &&
            belegDate >= earliestAllowed(today, OVERRIDE_CEILING_DAYS)

    /** The earliest acceptable Beleg date on [today] — inclusive. */
    fun earliestAllowed(
        today: LocalDate,
        maxAgeDays: Int,
    ): LocalDate = today.minus(DatePeriod(days = maxAgeDays))

    /**
     * @return null when [belegDate] is acceptable on [today], otherwise why it is not
     */
    fun check(
        belegDate: LocalDate,
        today: LocalDate,
        maxAgeDays: Int,
    ): KassenbuchBelegDateViolation? =
        when {
            belegDate > today -> KassenbuchBelegDateViolation.FUTURE
            belegDate < earliestAllowed(today, maxAgeDays) -> KassenbuchBelegDateViolation.TOO_OLD
            else -> null
        }

    /**
     * Parses the stored `DD.MM.YYYY` Beleg date, or null when it is absent or malformed.
     *
     * Null means "no date check" to every caller: the format itself is validated elsewhere (the
     * server repository, the forms' own format checks), and reporting it twice would make one bad
     * input show two differently worded problems.
     */
    fun parseGermanDate(ddMmYyyy: String?): LocalDate? {
        val parts = ddMmYyyy?.trim()?.split(".") ?: return null
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val year = parts[2].toIntOrNull() ?: return null
        return runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    /** A configured limit, or the default when it is absent or out of [ALLOWED_MAX_AGE_DAYS]. */
    fun effectiveMaxAgeDays(configured: Int?): Int = configured?.takeIf { it in ALLOWED_MAX_AGE_DAYS } ?: DEFAULT_MAX_AGE_DAYS
}
