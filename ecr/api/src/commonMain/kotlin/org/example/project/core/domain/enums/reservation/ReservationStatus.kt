package org.example.project.core.domain.enums.reservation

import kotlinx.serialization.Serializable

@Serializable
enum class ReservationStatus {
    CONFIRMED,
    SEATED,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
}
