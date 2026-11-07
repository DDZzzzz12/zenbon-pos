package org.example.project.features.reservation.domain.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.enums.reservation.ReservationRuleType
import org.example.project.features.reservation.domain.models.ReservationRule

interface ReservationRuleRepository {
    fun getById(id: Long): ReservationRule?

    fun getByDate(date: LocalDate): ReservationRule?

    fun getByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<ReservationRule>

    fun create(
        ruleDate: LocalDate,
        ruleType: ReservationRuleType,
        durationMinutes: Int?,
        timeSlots: String?,
        startTime: LocalTime?,
        endTime: LocalTime?,
    ): ReservationRule

    fun update(rule: ReservationRule): ReservationRule

    fun delete(id: Long)
}
