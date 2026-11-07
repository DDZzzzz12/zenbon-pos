package org.example.project.core.websocket

/** A frame queued for a single SSE connection's writer coroutine. */
sealed interface OutgoingFrame {
    data class Event(
        val event: SseEvent,
    ) : OutgoingFrame

    data class Comment(
        val text: String,
    ) : OutgoingFrame
}
