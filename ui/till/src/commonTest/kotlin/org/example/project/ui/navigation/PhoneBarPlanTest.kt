package org.example.project.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneBarPlanTest {
    private val allPrimaries =
        listOf(PosNavDestination.DASHBOARD, PosNavDestination.RESERVATIONS, PosNavDestination.NOTIFICATIONS)
    private val mgmt = listOf(PosNavDestination.REPORTS, PosNavDestination.FINANCIAL_DOCS)

    @Test
    fun barIsDashboardMenuReservationsAndNotificationsLeaveTheBar() {
        val plan = planPhoneBar(allPrimaries, mgmt, PosNavDestination.SETTINGS)
        assertEquals(listOf(PosNavDestination.DASHBOARD, PosNavDestination.RESERVATIONS), plan.barItems)
        assertEquals(1, plan.menuSlot)
    }

    @Test
    fun settingsJoinsTheMenuAfterManagementItems() {
        val plan = planPhoneBar(allPrimaries, mgmt, PosNavDestination.SETTINGS)
        assertEquals(mgmt + PosNavDestination.SETTINGS, plan.menuItems)
    }

    @Test
    fun noSettingsAccessMeansNoSettingsEntry() {
        val plan = planPhoneBar(allPrimaries, mgmt, settings = null)
        assertEquals(mgmt, plan.menuItems)
    }

    @Test
    fun withoutReservationAccessTheBarHasTwoSlotsAndMenuStaysInTheMiddle() {
        val plan =
            planPhoneBar(
                listOf(PosNavDestination.DASHBOARD, PosNavDestination.NOTIFICATIONS),
                emptyList(),
                settings = null,
            )
        assertEquals(listOf(PosNavDestination.DASHBOARD), plan.barItems)
        assertEquals(1, plan.menuSlot)
    }

    @Test
    fun emptyPrimariesPutsMenuFirst() {
        assertEquals(0, planPhoneBar(emptyList(), emptyList(), null).menuSlot)
        assertNull(planPhoneBar(emptyList(), emptyList(), null).menuItems.firstOrNull())
    }
}
