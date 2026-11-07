package org.example.project.features.datevexportlog

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.features.datevexportlog.data.service.PeriodEnumerator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeriodEnumeratorTest {
    private val utc = TimeZone.UTC

    @Test
    fun `MANUAL cadence returns empty`() {
        val out =
            PeriodEnumerator.missingPeriods(
                cursor = LocalDateTime.parse("2026-04-01T00:00:00"),
                now = LocalDateTime.parse("2026-05-15T00:00:00"),
                cadence = DatevExportCadence.MANUAL,
                tz = utc,
            )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `null cursor with WEEKLY returns the single most-recent completed week`() {
        // 2026-05-15 is a Friday in ISO W20 (current week, Mon 2026-05-11 -> Mon 2026-05-18).
        // Last completed = W19 = Mon 2026-05-04 -> Mon 2026-05-11.
        val out =
            PeriodEnumerator.missingPeriods(
                cursor = null,
                now = LocalDateTime.parse("2026-05-15T12:00:00"),
                cadence = DatevExportCadence.WEEKLY,
                tz = utc,
            )
        assertEquals(1, out.size)
        assertEquals("2026-W19", out[0].label)
    }

    @Test
    fun `null cursor with MONTHLY returns last completed month`() {
        val out =
            PeriodEnumerator.missingPeriods(
                cursor = null,
                now = LocalDateTime.parse("2026-05-15T12:00:00"),
                cadence = DatevExportCadence.MONTHLY,
                tz = utc,
            )
        assertEquals(1, out.size)
        assertEquals("2026-04", out[0].label)
    }

    @Test
    fun `WEEKLY catch-up walks forward from cursor and skips current incomplete week`() {
        // Cursor at end of 2026-W15 = Mon 2026-04-13 (which is the start of W16, exclusive end of W15).
        // Now is 2026-05-15 (W20 in progress). Expected catch-up: W16, W17, W18, W19.
        val out =
            PeriodEnumerator.missingPeriods(
                cursor = LocalDateTime.parse("2026-04-13T00:00:00"),
                now = LocalDateTime.parse("2026-05-15T12:00:00"),
                cadence = DatevExportCadence.WEEKLY,
                tz = utc,
            )
        assertEquals(listOf("2026-W16", "2026-W17", "2026-W18", "2026-W19"), out.map { it.label })
    }

    @Test
    fun `MONTHLY catch-up walks forward and skips current incomplete month`() {
        // Cursor at start of March 2026. Now mid-May. Expected: 2026-03, 2026-04. Skip 2026-05 (in progress).
        val out =
            PeriodEnumerator.missingPeriods(
                cursor = LocalDateTime.parse("2026-03-01T00:00:00"),
                now = LocalDateTime.parse("2026-05-15T12:00:00"),
                cadence = DatevExportCadence.MONTHLY,
                tz = utc,
            )
        assertEquals(listOf("2026-03", "2026-04"), out.map { it.label })
    }

    @Test
    fun `cursor exactly at a week boundary does not re-include the prior week`() {
        // Cursor at 2026-04-13T00:00 = exact start of W16. The prior week (W15) was last covered.
        // Walk should start at W16 (NOT W15 again).
        val out =
            PeriodEnumerator.missingPeriods(
                cursor = LocalDateTime.parse("2026-04-13T00:00:00"),
                now = LocalDateTime.parse("2026-05-15T12:00:00"),
                cadence = DatevExportCadence.WEEKLY,
                tz = utc,
            )
        // Same as previous test (the boundary case is the natural advance to next period).
        assertEquals(listOf("2026-W16", "2026-W17", "2026-W18", "2026-W19"), out.map { it.label })
    }

    @Test
    fun `cursor mid-period rolls forward to next complete period`() {
        // Cursor at 2026-04-15T12:00 (Wednesday of W16). Walk should start at W17 (skip the rest of W16).
        val out =
            PeriodEnumerator.missingPeriods(
                cursor = LocalDateTime.parse("2026-04-15T12:00:00"),
                now = LocalDateTime.parse("2026-05-15T12:00:00"),
                cadence = DatevExportCadence.WEEKLY,
                tz = utc,
            )
        assertEquals(listOf("2026-W17", "2026-W18", "2026-W19"), out.map { it.label })
    }
}
