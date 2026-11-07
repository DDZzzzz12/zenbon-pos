package org.example.project.features.dashboard.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardWorkInProgressTest {
    @Test
    fun `the grid and an unresolved route are idle`() {
        assertFalse(isDashboardWorkInProgress(null))
        assertFalse(isDashboardWorkInProgress("org.example.project.features.dashboard.presentation.navigation.DashboardMain"))
    }

    @Test
    fun `an order, billing or any other inner screen is work in progress`() {
        assertTrue(isDashboardWorkInProgress("org.example.project.features.dashboard.presentation.navigation.TableOrder/4/K001"))
        assertTrue(isDashboardWorkInProgress("org.example.project.features.dashboard.presentation.navigation.TableBilling/4"))
    }
}
