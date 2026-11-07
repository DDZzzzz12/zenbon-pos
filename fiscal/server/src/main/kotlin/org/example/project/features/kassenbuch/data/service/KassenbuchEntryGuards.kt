package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.core.utils.KassenbuchBelegDateViolation
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.currentTimeStamp

/**
 * The date checks an operator-created cash-book entry must pass.
 *
 * Applied **only** to `KassenbuchServiceImpl.createEntry` — the operator-facing
 * `POST /api/v1/kassenbuch`. Deliberately not applied to:
 *
 * - **corrections** (`createCorrection` calls `createSignedEntry` directly): a Storno of an entry
 *   inside a closed period is exactly what must stay possible, and `CashBookTransferServiceImpl`
 *   already states the same rule for transfer legs — "a locked leg MUST still be reversible";
 * - **the Z-Bon sweep**, which writes through `kassenbuchRepository.createEntry` and is the thing
 *   that *performs* the close;
 * - **transfers**, which use `createLeg` and already carry their own balance guard.
 *
 * The Kassenminus guard (F59) is NOT here: it lives in [KassenminusGuard], because unlike these date
 * checks it applies to every withdrawal — corrections' re-entries, tip payouts, transfers and the
 * Z-Bon's in-closing payouts included — and needs the book's balance under the advisory lock.
 */
object KassenbuchEntryGuards {
    /**
     * Parses the stored `DD.MM.YYYY` Beleg date, or null when it is absent or malformed — see
     * [KassenbuchBelegDateRule.parseGermanDate], which both entry forms use too. Null means "no date
     * check": `KassenbuchRepositoryImpl` rejects a malformed value moments later with its own message.
     */
    fun parseBelegDatum(ddMmYyyy: String?): LocalDate? = KassenbuchBelegDateRule.parseGermanDate(ddMmYyyy)

    /**
     * Rejects a Beleg date the shared [KassenbuchBelegDateRule] does not accept: in the future, or
     * further back than the shop's limit.
     *
     * **A date inside a closed period is accepted.** This used to refuse any Beleg date on or
     * before the book's last close (`409 KASSENBUCH_PERIOD_CLOSED`). For the fiscal Ladenkasse that
     * close is the latest Z-Bon compared by *date*, so after a Z-Bon an expense dated the same day,
     * or a receipt from yesterday handed in this morning, could not be booked at all
     * (E2E-2026-09-20 round 2). The period an entry belongs to is its booking time — `created_at`,
     * the TSE-attested moment — which is how the Z-Bon sweep, the Kassensturz lock, DSFinV-K and
     * the DATEV exports already select entries (`KassenbuchRepositoryImpl.getEntriesByDateRange`).
     * A late receipt therefore keeps its real date and lands in the open period; a closed period
     * is never touched.
     *
     * The age limit replaces the old guard's protection against a mistyped `01.01.1999`. Both
     * violations are 400: nothing about the shop's *state* is in play, the value itself is not an
     * acceptable Beleg date.
     *
     * @param belegDatum The parsed Beleg date
     * @param maxAgeDays The shop's limit, from `IShopConfigurationManager.getKassenbuchMaxBelegAgeDays`
     */
    fun requireAcceptableBelegDatum(
        belegDatum: LocalDate,
        maxAgeDays: Int,
    ) {
        requireAcceptableBelegDatum(belegDatum, maxAgeDays, approvedLateBookingReason = null)
    }

    /**
     * As above, but a Beleg date past the limit is accepted when the backdating override was
     * authorised — the route has already required `CASHBOOK_BACKDATE` or a manager's approval — and
     * a reason is given, as long as the date is within [KassenbuchBelegDateRule.OVERRIDE_CEILING_DAYS].
     *
     * @param approvedLateBookingReason The reason, passed only on the authorised override path; null
     *   on the ordinary path, where a date past the limit is refused
     * @return The reason to store on the entry: non-null only when the override was actually needed,
     *   so a reason sent with an ordinary date never lands on the entry
     */
    fun requireAcceptableBelegDatum(
        belegDatum: LocalDate,
        maxAgeDays: Int,
        approvedLateBookingReason: String?,
    ): String? {
        val today = currentTimeStamp().date
        val reason = approvedLateBookingReason?.trim()?.takeIf { it.isNotEmpty() }
        if (reason != null && KassenbuchBelegDateRule.needsOverride(belegDatum, today, maxAgeDays)) {
            if (reason.length > KassenbuchBelegDateRule.MAX_LATE_BOOKING_REASON_LENGTH) {
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "The late-booking reason may be at most ${KassenbuchBelegDateRule.MAX_LATE_BOOKING_REASON_LENGTH} characters",
                )
            }
            return reason
        }
        val violation = KassenbuchBelegDateRule.check(belegDatum, today, maxAgeDays) ?: return null
        val detail =
            when (violation) {
                KassenbuchBelegDateViolation.FUTURE -> "Beleg date $belegDatum is in the future (today is $today)"
                KassenbuchBelegDateViolation.TOO_OLD ->
                    "Beleg date $belegDatum is more than $maxAgeDays days old " +
                        "(earliest allowed: ${KassenbuchBelegDateRule.earliestAllowed(today, maxAgeDays)}). " +
                        "A genuinely late receipt up to ${KassenbuchBelegDateRule.OVERRIDE_CEILING_DAYS} days back " +
                        "can be booked with a reason and a manager's authority."
            }
        throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail)
    }
}
