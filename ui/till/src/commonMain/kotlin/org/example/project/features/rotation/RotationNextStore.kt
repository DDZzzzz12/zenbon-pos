package org.example.project.features.rotation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.websocket.NotificationDto

/**
 * Holds the "next employee in the rotation" the server last announced.
 *
 * The server's version is an in-memory counter seeded from its boot time, so it keeps rising across a
 * server restart. A live update is applied only when its version is strictly newer than the baseline; a
 * snapshot (reconnect reconcile) when it is at least as new, so a reconcile answer that arrives after a
 * newer live event cannot roll the chip back. The first value is taken whatever its version.
 */
class RotationNextStore {
    private data class State(
        val version: Long?,
        val next: RotationDto.EmployeeRefDto?,
    )

    private val state = MutableStateFlow(State(version = null, next = null))
    private val _next = MutableStateFlow<RotationDto.EmployeeRefDto?>(null)
    val next: StateFlow<RotationDto.EmployeeRefDto?> = _next.asStateFlow()

    fun apply(update: NotificationDto.RotationNextUpdateDto) {
        state.update { current ->
            val baseline = current.version
            val accepted =
                when {
                    baseline == null -> true
                    update.isSnapshot -> update.version >= baseline
                    else -> update.version > baseline
                }
            if (accepted) {
                State(update.version, update.next)
            } else {
                current
            }
        }
        _next.value = state.value.next
    }
}
