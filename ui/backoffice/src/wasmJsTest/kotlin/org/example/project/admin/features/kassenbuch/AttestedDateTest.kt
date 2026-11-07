package org.example.project.admin.features.kassenbuch

import kotlinx.datetime.LocalDate
import org.example.project.admin.features.kassenbuch.presentation.attestedDateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins which date a global-search hit is filed under, and therefore which period clicking it opens.
 *
 * This is worth a test because getting it wrong fails *silently and plausibly*: the operator clicks
 * a search result, the stepper moves, and the table comes back without the entry they clicked. There
 * is no error to see — it reads as though the search returned something that does not exist.
 *
 * The contract these cases defend is that the client picks the same date the server filters on.
 * `KassenbuchRepositoryImpl.getEntriesByDateRange` uses `header.createdAt.date in start..end`, on a
 * naive `datetime` column, with no timezone conversion.
 */
class AttestedDateTest {
    @Test
    fun readsTheDateOffTheTimestamp() {
        assertEquals(LocalDate(2026, 8, 9), attestedDateOf("2026-08-09T08:26:24"))
    }

    @Test
    fun toleratesEveryLocalDateTimeToStringShape() {
        // LocalDateTime.toString() drops seconds when they are zero and keeps fractional digits
        // when they are not — both reach the client verbatim through the mapper.
        assertEquals(LocalDate(2026, 8, 9), attestedDateOf("2026-08-09T08:26"))
        assertEquals(LocalDate(2026, 8, 9), attestedDateOf("2026-08-09T08:26:24.123456"))
    }

    @Test
    fun aLateEveningTimestampStaysOnItsOwnDay() {
        // The regression this guards: routing the string through an Instant and a local offset
        // would roll 23:50 into the next (or previous) day, and near a month boundary that is a
        // whole period away from where the entry actually lives.
        assertEquals(LocalDate(2026, 7, 31), attestedDateOf("2026-07-31T23:50:00"))
        assertEquals(LocalDate(2026, 8, 1), attestedDateOf("2026-08-01T00:10:00"))
    }

    @Test
    fun anUnreadableTimestampYieldsNull() {
        // The caller must still open the entry — it just cannot move the period.
        assertNull(attestedDateOf(""))
        assertNull(attestedDateOf("not-a-date"))
        assertNull(attestedDateOf("09.08.2026"))
    }

    @Test
    fun aGermanBelegDatumIsNotAccepted() {
        // belegDatum is DD.MM.YYYY and is the field this function must never be fed: it is
        // operator-typed and can name any date at all, including one outside the period the entry
        // is filed under. Rejecting it outright means a future mix-up fails loudly here rather
        // than quietly navigating somewhere wrong.
        assertNull(attestedDateOf("01.08.2026"))
    }
}
