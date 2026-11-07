package org.example.project.features.kassenbuch

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.data.service.KassenbuchEntryGuards
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.currentTimeStamp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Beleg-date guard for operator-created cash-book entries.
 *
 * The closed-period refusal this class used to pin is gone on purpose: the booking time decides the
 * period, so a late receipt keeps its date and books into the open period — see
 * [KassenbuchLateReceiptDateTest], which drives that through `createEntry`. What remains is the
 * shared [org.example.project.core.utils.KassenbuchBelegDateRule]: no future date, and no date older
 * than the shop's limit.
 */
class KassenbuchEntryGuardsTest {
    private val today = currentTimeStamp().date

    // ---- parseBelegDatum -------------------------------------------------

    @Test
    fun `parses the stored DD MM YYYY form`() {
        assertEquals(LocalDate(2026, 1, 2), KassenbuchEntryGuards.parseBelegDatum("02.01.2026"))
        assertEquals(LocalDate(2026, 12, 31), KassenbuchEntryGuards.parseBelegDatum("31.12.2026"))
    }

    @Test
    fun `malformed or absent dates yield null so the repository reports the format error`() {
        // Deliberately not an exception here: KassenbuchRepositoryImpl validates the format and a
        // second, differently worded error would make one bad input report two problems.
        for (bad in listOf(null, "", "2026-01-02", "32.01.2026", "02.13.2026", "x.y.z", "02.01")) {
            assertNull(KassenbuchEntryGuards.parseBelegDatum(bad), "expected null for '$bad'")
        }
    }

    // ---- Beleg date: future and age limit ---------------------------------

    @Test
    fun `a future-dated Beleg is rejected`() {
        val ex =
            assertFailsWith<PosException> {
                KassenbuchEntryGuards.requireAcceptableBelegDatum(today.plus(DatePeriod(days = 1)), maxAgeDays = 31)
            }
        assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)
    }

    @Test
    fun `today is acceptable`() {
        KassenbuchEntryGuards.requireAcceptableBelegDatum(today, maxAgeDays = 31)
    }

    @Test
    fun `a mistyped year is rejected by the age limit`() {
        // What the old closed-period guard protected against, now caught by the age limit alone.
        val ex =
            assertFailsWith<PosException> {
                KassenbuchEntryGuards.requireAcceptableBelegDatum(LocalDate(1999, 1, 1), maxAgeDays = 31)
            }
        assertEquals(PosErrorCode.VALIDATION_FAILED, ex.code)
    }

    @Test
    fun `the age limit is inclusive`() {
        KassenbuchEntryGuards.requireAcceptableBelegDatum(today.minus(DatePeriod(days = 31)), maxAgeDays = 31)
        assertFailsWith<PosException> {
            KassenbuchEntryGuards.requireAcceptableBelegDatum(today.minus(DatePeriod(days = 32)), maxAgeDays = 31)
        }
        Unit
    }
}
