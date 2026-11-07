package org.example.project.features.order

import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.order.domain.KitchenTicketWaiter
import org.example.project.features.order.domain.KitchenTicketWaiter.Staff
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** emu21 NOTE-x: station kitchen tickets read „K: TERMINAL-" — the shared station account, clipped. */
@DisplayName("Kitchen ticket waiter label")
class KitchenTicketWaiterTest {
    private val station = Staff("TERMINAL-DEVICE", EmployeeRole.TERMINAL_DEVICE)
    private val waiter = Staff("K001", EmployeeRole.CASHIER)

    @Test
    fun `a person's own order prints that person`() {
        assertEquals("M001", KitchenTicketWaiter.label(Staff("M001", EmployeeRole.MANAGER), waiter, "QA-Station"))
    }

    @Test
    fun `a station order prints the table's waiter, not the terminal account`() {
        assertEquals("K001", KitchenTicketWaiter.label(station, waiter, "QA-Station"))
    }

    @Test
    fun `with no person attached the station name stands in, never an empty label`() {
        assertEquals("QA-Station", KitchenTicketWaiter.label(station, station, "QA-Station"))
        assertEquals(KitchenTicketWaiter.STATION_FALLBACK, KitchenTicketWaiter.label(station, null, "  "))
        assertEquals(KitchenTicketWaiter.STATION_FALLBACK, KitchenTicketWaiter.label(station, null, null))
    }
}
