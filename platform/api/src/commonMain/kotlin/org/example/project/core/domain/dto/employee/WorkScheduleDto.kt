package org.example.project.core.domain.dto.employee

import kotlinx.serialization.Serializable

object WorkScheduleDto {
    @Serializable
    data class ShiftDto(
        val id: String? = null,
        val startTime: String, // "09:00"
        val endTime: String, // "17:00"
        val breakDuration: Int, // minutes
    )

    /**
     * One weekday of an employee's schedule. `dayOfWeek` is **1-7, Monday=1 .. Sunday=7**; shop
     * opening hours ([org.example.project.core.domain.dto.openinghours.OpeningHoursDto]) count
     * **0-6 (Monday=0 .. Sunday=6)** instead; never pass one convention to the other.
     */
    @Serializable
    data class DayScheduleDto(
        val dayOfWeek: Int, // 1=Mon, 7=Sun
        val shifts: List<ShiftDto>,
    )

    @Serializable
    data class WeeklyScheduleResponseDto(
        val employeeId: String,
        val contractedWeeklyHours: Double?,
        val scheduledWeeklyMinutes: Int,
        val days: List<DayScheduleDto>,
    )

    @Serializable
    data class UpdateDayRequestDto(
        val dayOfWeek: Int,
        val shifts: List<ShiftDto>,
    ) {
        fun validate() {
            require(dayOfWeek in 1..7) { "Day of week must be 1-7" }
            shifts.forEach { shift ->
                require(shift.startTime.matches(Regex("\\d{2}:\\d{2}"))) { "Invalid start time format" }
                require(shift.endTime.matches(Regex("\\d{2}:\\d{2}"))) { "Invalid end time format" }
                require(shift.breakDuration >= 0) { "Break duration cannot be negative" }
            }
        }
    }

    @Serializable
    data class UpdateWeekRequestDto(
        val days: List<UpdateDayRequestDto>,
    ) {
        fun validate() {
            days.forEach { it.validate() }
        }
    }
}
