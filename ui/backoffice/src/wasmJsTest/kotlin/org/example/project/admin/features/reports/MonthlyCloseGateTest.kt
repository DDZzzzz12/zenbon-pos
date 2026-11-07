package org.example.project.admin.features.reports

import org.example.project.admin.features.reports.presentation.components.closeDeltaToShow
import org.example.project.admin.features.reports.presentation.components.showsMonthlyClose
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The month-close block on Berichte follows `MONTHLY_CLOSE`, the capability `MonthlyCloseRules` puts
 * on `GET /api/v1/monthly-close` — not `CATALOG_MANAGE`, which it used to borrow via
 * `canManageProducts` (T5-5).
 */
class MonthlyCloseGateTest {
    @Test
    fun catalogManageAloneDoesNotShowTheBlock() {
        assertFalse(showsMonthlyClose(SetAccessPolicy(setOf(Capability.CATALOG_MANAGE))))
    }

    @Test
    fun monthlyCloseAloneShowsTheBlock() {
        assertTrue(showsMonthlyClose(SetAccessPolicy(setOf(Capability.MONTHLY_CLOSE))))
    }

    // FO-42: the deltas belong to the "Vergleich" toggle; with it off none may be drawn.
    @Test
    fun deltaIsHiddenWhileCompareIsOff() {
        assertNull(closeDeltaToShow(compareEnabled = false, deltaPercent = "-12.50"))
        assertNull(closeDeltaToShow(compareEnabled = false, deltaPercent = "3.00"))
    }

    @Test
    fun deltaIsShownWhileCompareIsOn() {
        assertEquals("-12.50", closeDeltaToShow(compareEnabled = true, deltaPercent = "-12.50"))
        assertNull(closeDeltaToShow(compareEnabled = true, deltaPercent = null))
    }

    @Test
    fun seededGroupsAreUnchanged() {
        assertTrue(showsMonthlyClose(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.MANAGER))))
        assertFalse(showsMonthlyClose(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.CASHIER))))
        listOf("ACCOUNTANT", "AUDITOR").forEach { code ->
            assertFalse(showsMonthlyClose(SetAccessPolicy(requireNotNull(CapabilityBundles.forGroup(code)))), code)
        }
    }
}
