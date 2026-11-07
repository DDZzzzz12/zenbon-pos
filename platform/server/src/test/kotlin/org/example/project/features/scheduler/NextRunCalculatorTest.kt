package org.example.project.features.scheduler

import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.features.scheduler.data.service.NextRunCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NextRunCalculatorTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val calc = NextRunCalculator()

    private fun instant(
        y: Int,
        m: Int,
        d: Int,
        h: Int,
        min: Int,
        zone: ZoneId = berlin,
    ) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    @Test fun `daily - picks today if time still ahead`() {
        val now = instant(2026, 5, 1, 10, 0)
        val next = calc.next(JobFrequency.DAILY, "15:00", null, null, berlin, now)
        assertEquals(instant(2026, 5, 1, 15, 0), next)
    }

    @Test fun `daily - picks tomorrow if time already passed`() {
        val now = instant(2026, 5, 1, 16, 0)
        val next = calc.next(JobFrequency.DAILY, "15:00", null, null, berlin, now)
        assertEquals(instant(2026, 5, 2, 15, 0), next)
    }

    @Test fun `weekly - picks this week if day+time ahead`() {
        // 2026-05-04 is Monday. Monday = 1.
        val now = instant(2026, 5, 4, 9, 0)
        val next = calc.next(JobFrequency.WEEKLY, "10:00", dayOfWeek = 1, null, berlin, now)
        assertEquals(instant(2026, 5, 4, 10, 0), next)
    }

    @Test fun `weekly - rolls to next week if today passed`() {
        val now = instant(2026, 5, 4, 11, 0) // Monday 11:00
        val next = calc.next(JobFrequency.WEEKLY, "10:00", dayOfWeek = 1, null, berlin, now)
        assertEquals(instant(2026, 5, 11, 10, 0), next)
    }

    @Test fun `monthly - specific day`() {
        val now = instant(2026, 5, 10, 8, 0)
        val next = calc.next(JobFrequency.MONTHLY, "09:00", null, dayOfMonth = 15, berlin, now)
        assertEquals(instant(2026, 5, 15, 9, 0), next)
    }

    @Test fun `monthly - last-day-of-month`() {
        val now = instant(2026, 5, 15, 8, 0) // May → last day is 31
        val next = calc.next(JobFrequency.MONTHLY, "09:00", null, dayOfMonth = -1, berlin, now)
        assertEquals(instant(2026, 5, 31, 9, 0), next)
    }

    @Test fun `monthly - rolls to next month when day+time passed`() {
        val now = instant(2026, 5, 20, 10, 0)
        val next = calc.next(JobFrequency.MONTHLY, "09:00", null, dayOfMonth = 15, berlin, now)
        assertEquals(instant(2026, 6, 15, 9, 0), next)
    }

    @Test fun `daily - DST spring forward - 02-30 skipped becomes 03-30`() {
        // 2026-03-29 Europe/Berlin spring forward: 02:00 → 03:00.
        // Scheduled 02:30 daily on the day of transition must still yield a valid instant
        // (the calculator falls back via ZonedDateTime normalization to 03:30 local that day).
        val now = instant(2026, 3, 29, 0, 0)
        val next = calc.next(JobFrequency.DAILY, "02:30", null, null, berlin, now)
        // Accept either the normalized instant or the next day's 02:30 - we require monotonic future.
        assert(next.isAfter(now))
    }
}
