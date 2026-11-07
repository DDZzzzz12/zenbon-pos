package org.example.project.core.websocket

import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.websocket.models.MessageTargeting
import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic event-id generator plus a bounded in-memory ring buffer of recent
 * events. Powers SSE `Last-Event-ID` replay. Self-evicts oldest entries past
 * [capacity]; gaps longer than the window are covered by client reconcile-on-connect.
 */
class NotificationEventLog(
    val capacity: Int = 256,
) {
    private val idGen = AtomicLong(0)
    private val lock = Any()
    private val buffer = ArrayDeque<SseEvent>(capacity)

    fun append(
        message: NotificationDto.NotificationMessage,
        targeting: MessageTargeting,
    ): SseEvent {
        val event = SseEvent(idGen.incrementAndGet(), message, targeting)
        synchronized(lock) {
            buffer.addLast(event)
            while (buffer.size > capacity) buffer.removeFirst()
        }
        return event
    }

    fun ephemeralId(): Long = idGen.incrementAndGet()

    fun replay(
        afterId: Long,
        predicate: (SseEvent) -> Boolean,
    ): List<SseEvent> = synchronized(lock) { buffer.filter { it.id > afterId && predicate(it) } }

    fun clear() = synchronized(lock) { buffer.clear() }
}
