package org.example.project.admin.features.dashboard

import org.example.project.admin.core.presentation.navigation.AdminNavItem
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.dashboard.presentation.dayFiguresNoRevenueKey
import org.example.project.admin.features.dashboard.presentation.dayFiguresTitleKey
import org.example.project.admin.features.dashboard.presentation.showDailyReportLink
import org.example.project.admin.features.dashboard.presentation.showRecentCashEntries
import org.example.project.admin.features.dashboard.presentation.showsFiscalDayCards
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The dashboard's *Heute* and *Tagesabschluss* cards open with `GET /api/z-bon/latest/datetime`
 * (`ZBON_READ`) and then read `/api/statistics/…` (`REPORT_READ`). Before the gate they were composed
 * for every principal, so one without `ZBON_READ` saw both cards fail on each load (E2E 2026-09-20
 * Low list). The gate must be exactly the server's.
 */
class FiscalDayGateTest {
    @Test
    fun withoutZBonReadTheCardsAreNotComposed() {
        assertFalse(showsFiscalDayCards(SetAccessPolicy(setOf(Capability.REPORT_READ))))
    }

    @Test
    fun withoutReportReadTheCardsAreNotComposed() {
        assertFalse(showsFiscalDayCards(SetAccessPolicy(setOf(Capability.ZBON_READ))))
    }

    @Test
    fun bothCapabilitiesShowTheCards() {
        assertTrue(showsFiscalDayCards(SetAccessPolicy(setOf(Capability.ZBON_READ, Capability.REPORT_READ))))
    }

    @Test
    fun seededGroups() {
        assertTrue(showsFiscalDayCards(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.MANAGER))))
        assertTrue(showsFiscalDayCards(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.ADMIN))))
        assertTrue(showsFiscalDayCards(SetAccessPolicy(requireNotNull(CapabilityBundles.forGroup("AUDITOR")))))
        // Order taking only: no Z-Bon, no reports — the cards would only ever 403.
        assertFalse(showsFiscalDayCards(SetAccessPolicy(requireNotNull(CapabilityBundles.forGroup("ORDER_ONLY")))))
    }

    @Test
    fun dailyReportLinkNeedsReportAllRead() {
        assertTrue(showDailyReportLink(SetAccessPolicy(setOf(Capability.REPORT_ALL_READ))))
        assertFalse(showDailyReportLink(SetAccessPolicy(setOf(Capability.REPORT_READ, Capability.ZBON_READ))))
    }

    @Test
    fun recentCashEntriesNeedCashbookRead() {
        assertTrue(showRecentCashEntries(SetAccessPolicy(setOf(Capability.CASHBOOK_READ))))
        assertFalse(showRecentCashEntries(SetAccessPolicy(setOf(Capability.REPORT_READ))))
    }

    @Test
    fun emptyCapabilitySetIsUnknownAndFallsBackToTheRole() {
        // An older server sends no capabilities; the policy then answers from the role, never "nothing".
        val unknown = SetAccessPolicy(emptySet())
        assertEquals(AdminNavItem.DAILY_REPORT.isVisibleTo(unknown), showDailyReportLink(unknown))
    }

    @Test
    fun ownFiguresGetTheirOwnTitleAndEmptyText() {
        assertEquals(AdminStringKeys.DashboardTodayTitle, dayFiguresTitleKey(true))
        assertEquals(AdminStringKeys.DashboardTodayTitleOwn, dayFiguresTitleKey(false))
        assertEquals(AdminStringKeys.DashboardTodayNoRevenue, dayFiguresNoRevenueKey(true))
        assertEquals(AdminStringKeys.DashboardTodayNoRevenueOwn, dayFiguresNoRevenueKey(false))
        assertNotEquals(dayFiguresTitleKey(true), dayFiguresTitleKey(false))
    }
}
