package org.example.project.core.websocket

import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseConnectionRegistryTest {
    private fun conn(
        id: String,
        emp: String,
    ) = SseConnection(id, emp, null, Channel<OutgoingFrame>(Channel.UNLIMITED))

    @Test
    fun `register and lookup by employee`() {
        val reg = SseConnectionRegistry()
        reg.register(conn("c1", "e1"))
        reg.register(conn("c2", "e2"))
        assertEquals(listOf("c1"), reg.forEmployee("e1").map { it.id })
        assertEquals(2, reg.count())
    }

    @Test
    fun `remove returns the removed connection and drops it`() {
        val reg = SseConnectionRegistry()
        reg.register(conn("c1", "e1"))
        assertEquals("c1", reg.remove("c1")?.id)
        assertNull(reg.remove("c1"))
        assertEquals(0, reg.count())
    }
}
