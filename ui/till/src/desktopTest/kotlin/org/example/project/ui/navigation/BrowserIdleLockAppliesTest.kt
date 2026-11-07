package org.example.project.ui.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins who the browser idle lock guards: every person on a browser till, never a station or a native client. */
class BrowserIdleLockAppliesTest {
    @Test
    fun `a person signed in on a browser till is idle-locked whatever their role`() {
        assertTrue(browserIdleLockApplies(isBrowserClient = true, isStation = false))
    }

    @Test
    fun `an order station is never idle-locked - it has no person session to end`() {
        assertFalse(browserIdleLockApplies(isBrowserClient = true, isStation = true))
    }

    @Test
    fun `a native client has no idle lock`() {
        assertFalse(browserIdleLockApplies(isBrowserClient = false, isStation = false))
        assertFalse(browserIdleLockApplies(isBrowserClient = false, isStation = true))
    }
}
