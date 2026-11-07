package org.example.project.features.openinghours.domain.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto

interface OpeningHoursService {
    suspend fun getAll(): List<OpeningHoursDto.WeeklyResponseDto>

    suspend fun bulkUpdate(request: OpeningHoursDto.BulkUpdateRequestDto): List<OpeningHoursDto.WeeklyResponseDto>

    suspend fun getOverridesByDateRange(
        startDate: String,
        endDate: String,
    ): List<OpeningHoursDto.OverrideResponseDto>

    suspend fun createOverride(request: OpeningHoursDto.CreateOverrideRequestDto): OpeningHoursDto.OverrideResponseDto

    suspend fun updateOverride(
        id: Long,
        request: OpeningHoursDto.UpdateOverrideRequestDto,
    ): OpeningHoursDto.OverrideResponseDto

    suspend fun deleteOverride(id: Long)

    /**
     * Resolve the effective opening hours for a given date.
     * Must be called within an existing dbQuery transaction context.
     * Returns null if no hours configured (treat as always open).
     */
    data class ResolvedHours(
        val isOpen: Boolean,
        val slots: List<TimeSlot> = emptyList(),
        val reason: String? = null,
    ) {
        data class TimeSlot(
            val openTime: LocalTime,
            val closeTime: LocalTime,
        )

        fun isTimeWithin(time: LocalTime): Boolean = slots.any { time >= it.openTime && time < it.closeTime }

        val openTime: LocalTime? get() = slots.minByOrNull { it.openTime }?.openTime
        val closeTime: LocalTime? get() = slots.maxByOrNull { it.closeTime }?.closeTime
    }

    suspend fun resolveForDate(date: LocalDate): ResolvedHours?
}
