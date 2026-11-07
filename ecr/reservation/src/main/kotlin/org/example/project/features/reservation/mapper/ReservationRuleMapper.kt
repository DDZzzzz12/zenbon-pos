package org.example.project.features.reservation.mapper

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.features.reservation.domain.models.ReservationRule

fun ReservationRule.toResponse(): ReservationRuleDto.ResponseDto {
    val slots =
        this.timeSlots?.let {
            try {
                Json.decodeFromString<List<String>>(it)
            } catch (_: Exception) {
                null
            }
        }
    return ReservationRuleDto.ResponseDto(
        id = this.id.value,
        ruleDate = this.ruleDate.toString(),
        ruleType = this.ruleType,
        durationMinutes = this.durationMinutes,
        timeSlots = slots,
        startTime = this.startTime?.toString(),
        endTime = this.endTime?.toString(),
        createdAt = this.createdAt.toString(),
    )
}
