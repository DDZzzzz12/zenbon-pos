package org.example.project.core.websocket

import org.example.project.core.domain.dto.websocket.NotificationDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingBillingRequestStoreTest {
    private fun req(
        tableId: Long,
        ts: String = "2026-01-01T00:00:00Z",
    ) = NotificationDto.BillingRequestNotificationDto(
        timestamp = ts,
        tableId = tableId,
        tableNumber = "T$tableId",
        requestingEmployeeNumber = "100",
    )

    @BeforeTest
    fun setup() = PendingBillingRequestStore.reset()

    @AfterTest
    fun tearDown() = PendingBillingRequestStore.reset()

    @Test
    fun `add then getForEmployee returns the request`() {
        PendingBillingRequestStore.add("emp-1", req(5))
        val pending = PendingBillingRequestStore.getForEmployee("emp-1")
        assertEquals(1, pending.size)
        assertEquals(5L, pending.first().tableId)
    }

    @Test
    fun `a second add for the same table supersedes the first`() {
        PendingBillingRequestStore.add("emp-1", req(5, ts = "2026-01-01T00:00:00Z"))
        PendingBillingRequestStore.add("emp-1", req(5, ts = "2026-01-01T01:00:00Z"))
        val pending = PendingBillingRequestStore.getForEmployee("emp-1")
        assertEquals(1, pending.size)
        assertEquals("2026-01-01T01:00:00Z", pending.first().timestamp)
    }

    @Test
    fun `add for different tables keeps both`() {
        PendingBillingRequestStore.add("emp-1", req(5))
        PendingBillingRequestStore.add("emp-1", req(6))
        assertEquals(2, PendingBillingRequestStore.getForEmployee("emp-1").size)
    }

    @Test
    fun `clearForTable removes the pending request across employees`() {
        PendingBillingRequestStore.add("emp-1", req(5))
        PendingBillingRequestStore.add("emp-2", req(5))
        PendingBillingRequestStore.clearForTable(5)
        assertTrue(PendingBillingRequestStore.getForEmployee("emp-1").isEmpty())
        assertTrue(PendingBillingRequestStore.getForEmployee("emp-2").isEmpty())
    }

    @Test
    fun `getForEmployee returns empty for unknown employee`() {
        assertTrue(PendingBillingRequestStore.getForEmployee("nobody").isEmpty())
    }
}
