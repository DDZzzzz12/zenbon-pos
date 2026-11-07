package org.example.project.core.websocket

import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.websocket.models.MessageTargeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationEventLogTest {
    private fun msg(n: String) = NotificationDto.WebSocketErrorDto(timestamp = "t", errorCode = "E", message = n)

    @Test
    fun `append assigns strictly increasing ids`() {
        val log = NotificationEventLog()
        val a = log.append(msg("a"), MessageTargeting.BroadcastAll)
        val b = log.append(msg("b"), MessageTargeting.BroadcastAll)
        assertEquals(a.id + 1, b.id)
    }

    @Test
    fun `replay returns only events after the given id matching predicate`() {
        val log = NotificationEventLog()
        val a = log.append(msg("a"), MessageTargeting.BroadcastAll)
        val b = log.append(msg("b"), MessageTargeting.BroadcastAll)
        assertEquals(listOf(b.id), log.replay(afterId = a.id) { true }.map { it.id })
    }

    @Test
    fun `replay drops events evicted past capacity`() {
        val log = NotificationEventLog(capacity = 4)
        repeat(9) { log.append(msg("x"), MessageTargeting.BroadcastAll) }
        assertTrue(log.replay(afterId = 0) { true }.size <= 4)
    }

    @Test
    fun `ephemeralId advances the counter without storing`() {
        val log = NotificationEventLog()
        val before = log.append(msg("a"), MessageTargeting.BroadcastAll).id
        val eph = log.ephemeralId()
        val after = log.append(msg("b"), MessageTargeting.BroadcastAll).id
        assertTrue(eph > before && after > eph)
        assertTrue(log.replay(afterId = 0) { true }.none { it.id == eph })
    }
}
