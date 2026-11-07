package org.example.project.core.websocket

import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.websocket.models.MessageTargeting

/** A notification event with a monotonic id and its delivery targeting. */
data class SseEvent(
    val id: Long,
    val message: NotificationDto.NotificationMessage,
    val targeting: MessageTargeting,
)
