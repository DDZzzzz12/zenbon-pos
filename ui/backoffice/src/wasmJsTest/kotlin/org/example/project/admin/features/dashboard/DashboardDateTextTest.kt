package org.example.project.admin.features.dashboard

import kotlinx.datetime.LocalDate
import org.example.project.admin.features.dashboard.presentation.dashboardDateText
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/** The header date carries its weekday in every language, long on a wide window and short on a phone. */
class DashboardDateTextTest {
    private val wednesday = LocalDate(2026, 10, 21)
    private val sunday = LocalDate(2026, 1, 4)

    @Test
    fun `german long and short`() {
        assertEquals("Mittwoch, 21. Oktober 2026", dashboardDateText(wednesday, Language.GERMAN, compact = false))
        assertEquals("Mi., 21.10.2026", dashboardDateText(wednesday, Language.GERMAN, compact = true))
    }

    @Test
    fun `english long and short`() {
        assertEquals("Wednesday, 21 October 2026", dashboardDateText(wednesday, Language.ENGLISH, compact = false))
        assertEquals("Wed, 21 Oct 2026", dashboardDateText(wednesday, Language.ENGLISH, compact = true))
    }

    @Test
    fun `chinese long and short`() {
        assertEquals("2026年10月21日 星期三", dashboardDateText(wednesday, Language.CHINESE, compact = false))
        assertEquals("2026年10月21日 周三", dashboardDateText(wednesday, Language.CHINESE, compact = true))
    }

    @Test
    fun `week ends on sunday and a single digit day stays unpadded in the long form`() {
        assertEquals("Sonntag, 4. Januar 2026", dashboardDateText(sunday, Language.GERMAN, compact = false))
        assertEquals("So., 04.01.2026", dashboardDateText(sunday, Language.GERMAN, compact = true))
    }
}
