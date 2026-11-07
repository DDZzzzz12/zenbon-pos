package org.example.project.features.contract

import kotlinx.datetime.LocalDate
import org.example.project.features.contract.data.service.InstalmentSchedule
import org.example.project.features.contract.domain.models.ContractInterval
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every bug in contract handling will live here, so this is where the coverage goes: February has
 * no 31st, quarterly counts from the contract's own start month, and an end date mid-period must not
 * yield one instalment too many.
 *
 * The function is pure and takes its window as an argument — no clock — so each case is two lines.
 */
@DisplayName("Instalment schedule")
class InstalmentScheduleTest {
    private fun dates(
        start: LocalDate,
        interval: ContractInterval,
        dueDay: Int,
        from: LocalDate,
        to: LocalDate,
        end: LocalDate? = null,
    ) = InstalmentSchedule.dueDatesIn(
        startDate = start,
        endDate = end,
        interval = interval,
        dueDay = dueDay,
        dueWeekday = null,
        windowStart = from,
        windowEnd = to,
    )

    @Test
    fun `a monthly contract yields one date per month on its due day`() {
        assertEquals(
            listOf(LocalDate(2026, 3, 15), LocalDate(2026, 4, 15), LocalDate(2026, 5, 15)),
            dates(
                start = LocalDate(2026, 1, 15),
                interval = ContractInterval.MONTHLY,
                dueDay = 15,
                from = LocalDate(2026, 3, 1),
                to = LocalDate(2026, 5, 31),
            ),
        )
    }

    @Test
    fun `a due day past the end of the month clamps to the last day`() {
        assertEquals(
            listOf(LocalDate(2026, 1, 31), LocalDate(2026, 2, 28), LocalDate(2026, 3, 31)),
            dates(
                start = LocalDate(2026, 1, 31),
                interval = ContractInterval.MONTHLY,
                dueDay = 31,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 3, 31),
            ),
            "February is the 28th, not skipped — the rent is still owed",
        )
    }

    @Test
    fun `February 29th appears in a leap year`() {
        assertEquals(
            listOf(LocalDate(2028, 2, 29)),
            dates(
                start = LocalDate(2028, 1, 30),
                interval = ContractInterval.MONTHLY,
                dueDay = 30,
                from = LocalDate(2028, 2, 1),
                to = LocalDate(2028, 2, 29),
            ),
        )
    }

    @Test
    fun `quarterly counts from the contract's own start month, not from January`() {
        assertEquals(
            listOf(LocalDate(2026, 2, 1), LocalDate(2026, 5, 1), LocalDate(2026, 8, 1), LocalDate(2026, 11, 1)),
            dates(
                start = LocalDate(2026, 2, 1),
                interval = ContractInterval.QUARTERLY,
                dueDay = 1,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 12, 31),
            ),
        )
    }

    @Test
    fun `annual yields one date a year`() {
        assertEquals(
            listOf(LocalDate(2026, 6, 10), LocalDate(2027, 6, 10)),
            dates(
                start = LocalDate(2026, 6, 10),
                interval = ContractInterval.ANNUAL,
                dueDay = 10,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2027, 12, 31),
            ),
        )
    }

    @Test
    fun `nothing falls due before the contract starts`() {
        val result =
            dates(
                start = LocalDate(2026, 6, 1),
                interval = ContractInterval.MONTHLY,
                dueDay = 1,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 12, 31),
            )
        assertTrue(result.all { it >= LocalDate(2026, 6, 1) }, result.toString())
        assertEquals(LocalDate(2026, 6, 1), result.first())
    }

    @Test
    fun `nothing falls due after the contract ends`() {
        assertEquals(
            listOf(LocalDate(2026, 1, 1), LocalDate(2026, 2, 1), LocalDate(2026, 3, 1)),
            dates(
                start = LocalDate(2026, 1, 1),
                interval = ContractInterval.MONTHLY,
                dueDay = 1,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 12, 31),
                end = LocalDate(2026, 3, 15),
            ),
            "the March instalment is due before the end date; April is not",
        )
    }

    @Test
    fun `an end date on a due date still includes that instalment`() {
        val result =
            dates(
                start = LocalDate(2026, 1, 1),
                interval = ContractInterval.MONTHLY,
                dueDay = 1,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 12, 31),
                end = LocalDate(2026, 3, 1),
            )
        assertEquals(LocalDate(2026, 3, 1), result.last(), "the contract runs to and including its end date")
    }

    @Test
    fun `an empty window yields nothing`() {
        assertEquals(
            emptyList(),
            dates(
                start = LocalDate(2026, 1, 1),
                interval = ContractInterval.MONTHLY,
                dueDay = 1,
                from = LocalDate(2026, 5, 2),
                to = LocalDate(2026, 5, 31),
            ),
        )
    }

    @Test
    fun `a one-time contract falls due once, on its due day in the start month`() {
        val dates =
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 3, 5),
                endDate = null,
                interval = ContractInterval.ONE_TIME,
                dueDay = 15,
                dueWeekday = null,
                windowStart = LocalDate(2026, 1, 1),
                windowEnd = LocalDate(2026, 12, 31),
            )
        assertEquals(listOf(LocalDate(2026, 3, 15)), dates)
    }

    @Test
    fun `a one-time due day clamps to the end of a short month`() {
        val dates =
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 2, 1),
                endDate = null,
                interval = ContractInterval.ONE_TIME,
                dueDay = 31,
                dueWeekday = null,
                windowStart = LocalDate(2026, 1, 1),
                windowEnd = LocalDate(2026, 12, 31),
            )
        assertEquals(listOf(LocalDate(2026, 2, 28)), dates)
    }

    @Test
    fun `a one-time date before the start date is not produced`() {
        // dueDay 3 in a contract starting on the 20th falls before the start; it is not owed.
        val dates =
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 3, 20),
                endDate = null,
                interval = ContractInterval.ONE_TIME,
                dueDay = 3,
                dueWeekday = null,
                windowStart = LocalDate(2026, 1, 1),
                windowEnd = LocalDate(2026, 12, 31),
            )
        assertEquals(emptyList(), dates)
    }

    @Test
    fun `a weekly contract falls due on its weekday, every seven days`() {
        // 2026-03-02 is a Monday. Weekday 3 = Wednesday.
        val dates =
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 3, 2),
                endDate = null,
                interval = ContractInterval.WEEKLY,
                dueDay = 1,
                dueWeekday = 3,
                windowStart = LocalDate(2026, 3, 1),
                windowEnd = LocalDate(2026, 3, 31),
            )
        assertEquals(
            listOf(
                LocalDate(2026, 3, 4),
                LocalDate(2026, 3, 11),
                LocalDate(2026, 3, 18),
                LocalDate(2026, 3, 25),
            ),
            dates,
        )
    }

    @Test
    fun `a weekly contract starting on its own weekday starts that same day`() {
        // 2026-03-04 is a Wednesday, and the weekday asked for is Wednesday.
        val dates =
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 3, 4),
                endDate = null,
                interval = ContractInterval.WEEKLY,
                dueDay = 1,
                dueWeekday = 3,
                windowStart = LocalDate(2026, 3, 1),
                windowEnd = LocalDate(2026, 3, 12),
            )
        assertEquals(listOf(LocalDate(2026, 3, 4), LocalDate(2026, 3, 11)), dates)
    }

    @Test
    fun `a weekly contract crosses a month boundary`() {
        val dates =
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 3, 25),
                endDate = null,
                interval = ContractInterval.WEEKLY,
                dueDay = 1,
                dueWeekday = 3,
                windowStart = LocalDate(2026, 3, 1),
                windowEnd = LocalDate(2026, 4, 10),
            )
        assertEquals(
            listOf(LocalDate(2026, 3, 25), LocalDate(2026, 4, 1), LocalDate(2026, 4, 8)),
            dates,
        )
    }

    @Test
    fun `a weekly contract stops at its end date`() {
        val dates =
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 3, 2),
                endDate = LocalDate(2026, 3, 12),
                interval = ContractInterval.WEEKLY,
                dueDay = 1,
                dueWeekday = 3,
                windowStart = LocalDate(2026, 3, 1),
                windowEnd = LocalDate(2026, 3, 31),
            )
        assertEquals(listOf(LocalDate(2026, 3, 4), LocalDate(2026, 3, 11)), dates)
    }

    @Test
    fun `a weekly contract without a weekday is rejected rather than producing nothing`() {
        assertFailsWith<IllegalArgumentException> {
            InstalmentSchedule.dueDatesIn(
                startDate = LocalDate(2026, 3, 2),
                endDate = null,
                interval = ContractInterval.WEEKLY,
                dueDay = 1,
                dueWeekday = null,
                windowStart = LocalDate(2026, 3, 1),
                windowEnd = LocalDate(2026, 3, 31),
            )
        }
        Unit
    }
}
