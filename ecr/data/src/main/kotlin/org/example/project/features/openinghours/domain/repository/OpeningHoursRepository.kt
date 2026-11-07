package org.example.project.features.openinghours.domain.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.example.project.features.openinghours.domain.models.OpeningHours
import org.example.project.features.openinghours.domain.models.OpeningHoursOverride

interface OpeningHoursRepository {
    fun getAll(shopId: Long): List<OpeningHours>

    fun getByDayOfWeek(
        dayOfWeek: Int,
        shopId: Long,
    ): List<OpeningHours>

    fun createSlot(
        dayOfWeek: Int,
        openTime: LocalTime,
        closeTime: LocalTime,
        isClosed: Boolean,
        shopId: Long,
    ): OpeningHours

    fun deleteAll(shopId: Long)

    fun getOverrideById(id: Long): OpeningHoursOverride?

    fun getOverrideByDate(
        date: LocalDate,
        shopId: Long,
    ): OpeningHoursOverride?

    fun getOverridesByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        shopId: Long,
    ): List<OpeningHoursOverride>

    fun createOverride(
        date: LocalDate,
        openTime: LocalTime?,
        closeTime: LocalTime?,
        isClosed: Boolean,
        reason: String?,
        shopId: Long,
    ): OpeningHoursOverride

    fun updateOverride(override: OpeningHoursOverride): OpeningHoursOverride

    fun deleteOverride(id: Long)
}
