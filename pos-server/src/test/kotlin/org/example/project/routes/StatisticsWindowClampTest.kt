package org.example.project.routes

import kotlinx.datetime.LocalDateTime
import org.example.project.features.statistics.domain.routes.clampToTradingDay
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * How far back a caller without `REPORT_ALL_READ` may look.
 *
 * `seesEveryone` bounds *whose* rows come back; this bounds *when*, which nothing did before —
 * `start_date` was required and otherwise unconstrained, so a cashier could ask for any window of
 * their own figures.
 *
 * Tested here rather than through the route because the route can only be observed as a 200, which
 * it returns whether or not the clamp fires. The first version of these assertions did exactly that
 * and proved nothing.
 */
@DisplayName("Statistics window clamp")
class StatisticsWindowClampTest {
    private val may = LocalDateTime.parse("2026-05-01T00:00:00")
    private val tradingDay = LocalDateTime.parse("2026-06-10T02:00:00")
    private val afterTradingDay = LocalDateTime.parse("2026-06-10T18:00:00")

    @Test
    fun `a request reaching before the trading day is raised to it`() {
        assertEquals(tradingDay, clampToTradingDay(may, tradingDay))
    }

    @Test
    fun `a narrower request is left alone`() {
        // The clamp is a floor on how far back you may look, not an instruction to always widen to
        // the whole trading day. Asking for the last hour must return the last hour.
        assertEquals(afterTradingDay, clampToTradingDay(afterTradingDay, tradingDay))
    }

    @Test
    fun `a boundary exactly equal to the request changes nothing`() {
        assertEquals(tradingDay, clampToTradingDay(tradingDay, tradingDay))
    }

    @Test
    fun `no boundary means no clamp`() {
        // Two callers arrive here with null. One holds REPORT_ALL_READ and may see the whole
        // history. The other is a shop that has never closed a Z-Bon — a new shop must not lose its
        // own figures because it has not cashed up yet.
        assertEquals(may, clampToTradingDay(may, null))
    }
}
