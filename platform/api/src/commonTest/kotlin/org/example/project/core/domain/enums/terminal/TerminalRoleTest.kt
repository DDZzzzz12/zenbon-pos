package org.example.project.core.domain.enums.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalRoleTest {
    @Test
    fun `cashier and order station are fiscal, displays are not`() {
        assertTrue(TerminalRole.CASHIER.defaultFiscal)
        assertTrue(TerminalRole.ORDER_STATION.defaultFiscal)
        assertFalse(TerminalRole.KITCHEN.defaultFiscal)
        assertFalse(TerminalRole.CUSTOMER_DISPLAY.defaultFiscal)
        assertFalse(TerminalRole.KIOSK.defaultFiscal)
    }

    @Test
    fun `only the cashier till is login-full`() {
        assertFalse(TerminalRole.CASHIER.loginLess)
        assertTrue(TerminalRole.ORDER_STATION.loginLess)
        assertTrue(TerminalRole.KITCHEN.loginLess)
    }

    @Test
    fun `fromCode is case-insensitive and defaults to CASHIER for unknown`() {
        assertEquals(TerminalRole.ORDER_STATION, TerminalRole.fromCode("order_station"))
        assertEquals(TerminalRole.CASHIER, TerminalRole.fromCode("nonsense"))
        assertEquals(TerminalRole.CASHIER, TerminalRole.fromCode(null))
    }

    @Test
    fun `parseOrNull names a role or nothing, never a default`() {
        assertEquals(TerminalRole.KITCHEN, TerminalRole.parseOrNull(" kitchen "))
        assertNull(TerminalRole.parseOrNull("KITCHEN_DISPLAY"))
        assertNull(TerminalRole.parseOrNull(""))
        assertNull(TerminalRole.parseOrNull(null))
    }
}
