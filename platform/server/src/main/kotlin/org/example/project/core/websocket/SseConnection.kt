package org.example.project.core.websocket

import kotlinx.coroutines.channels.SendChannel

/** A live SSE connection: an employee plus the channel feeding its writer coroutine. */
data class SseConnection(
    val id: String,
    val employeeId: String,
    val deviceId: String?,
    val outgoing: SendChannel<OutgoingFrame>,
    val connectedAt: Long = System.currentTimeMillis(),
)
