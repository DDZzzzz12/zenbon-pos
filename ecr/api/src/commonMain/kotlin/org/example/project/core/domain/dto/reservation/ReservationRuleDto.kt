package org.example.project.core.domain.dto.reservation

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.reservation.ReservationRuleType

object ReservationRuleDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val ruleDate: String,
        val ruleType: ReservationRuleType,
        val durationMinutes: Int? = null,
        val timeSlots: List<String>? = null,
        val startTime: String? = null,
        val endTime: String? = null,
        val createdAt: String,
    ) {
        companion object
    }

    @Serializable
    data class CreateRequestDto(
        val ruleDate: String,
        val ruleType: ReservationRuleType,
        val durationMinutes: Int? = null,
        val timeSlots: List<String>? = null,
        val startTime: String? = null,
        val endTime: String? = null,
    ) {
        fun validate() {
            when (ruleType) {
                ReservationRuleType.FIXED_DURATION -> {
                    requireNotNull(durationMinutes) { "Duration is required for FIXED_DURATION rules" }
                    require(durationMinutes > 0) { "Duration must be positive" }
                    requireNotNull(startTime) { "Start time is required for FIXED_DURATION rules" }
                    requireNotNull(endTime) { "End time is required for FIXED_DURATION rules" }
                }
                ReservationRuleType.TIME_SLOTS -> {
                    requireNotNull(timeSlots) { "Time slots are required for TIME_SLOTS rules" }
                    require(timeSlots.isNotEmpty()) { "At least one time slot is required" }
                }
            }
        }
    }

    @Serializable
    data class UpdateRequestDto(
        val ruleType: ReservationRuleType? = null,
        val durationMinutes: Int? = null,
        val timeSlots: List<String>? = null,
        val startTime: String? = null,
        val endTime: String? = null,
    )
}
