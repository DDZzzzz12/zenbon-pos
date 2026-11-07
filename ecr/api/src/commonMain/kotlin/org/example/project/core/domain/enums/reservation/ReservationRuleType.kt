package org.example.project.core.domain.enums.reservation

import kotlinx.serialization.Serializable

@Serializable
enum class ReservationRuleType {
    FIXED_DURATION,
    TIME_SLOTS,
}
