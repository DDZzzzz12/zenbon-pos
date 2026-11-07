package org.example.project.core.domain.dto.openinghours

import kotlinx.serialization.Serializable

object OpeningHoursDto {
    @Serializable
    data class WeeklyResponseDto(
        val id: Long,
        val dayOfWeek: Int,
        val openTime: String,
        val closeTime: String,
        val isClosed: Boolean,
        val shopId: Long? = null,
        val createdAt: String,
    )

    @Serializable
    data class BulkUpdateRequestDto(
        val days: List<DaySchedule>,
    ) {
        fun validate() {
            require(days.isNotEmpty()) { "At least one day schedule is required" }
            days.forEach { it.validate() }
            // Validate no overlapping time slots within the same day
            days.filter { !it.isClosed }.groupBy { it.dayOfWeek }.forEach { (day, slots) ->
                val sorted = slots.sortedBy { it.openTime }
                for (i in 0 until sorted.size - 1) {
                    require(sorted[i].closeTime <= sorted[i + 1].openTime) {
                        "Time slots overlap on day $day"
                    }
                }
            }
        }
    }

    /**
     * One weekday of the opening hours.
     *
     * `dayOfWeek` is **0-6, Monday=0 .. Sunday=6**. Employee work schedules
     * ([org.example.project.core.domain.dto.employee.WorkScheduleDto]) and the scheduler's weekly
     * jobs count **1-7 (Monday=1 .. Sunday=7)** instead; never pass one convention to the other.
     */
    @Serializable
    data class DaySchedule(
        /** 0 = Monday … 6 = Sunday. Employee work schedules (`WorkScheduleDto`) count 1 = Monday … 7 = Sunday. */
        val dayOfWeek: Int,
        val openTime: String,
        val closeTime: String,
        val isClosed: Boolean = false,
    ) {
        fun validate() {
            require(dayOfWeek in 0..6) { "dayOfWeek must be 0-6 (Monday-Sunday)" }
            if (!isClosed) {
                require(openTime.isNotBlank()) { "openTime required when not closed" }
                require(closeTime.isNotBlank()) { "closeTime required when not closed" }
                require(openTime < closeTime) { "openTime must be before closeTime" }
            }
        }
    }

    @Serializable
    data class OverrideResponseDto(
        val id: Long,
        val overrideDate: String,
        val openTime: String?,
        val closeTime: String?,
        val isClosed: Boolean,
        val reason: String?,
        val shopId: Long? = null,
        val createdAt: String,
    )

    @Serializable
    data class CreateOverrideRequestDto(
        val overrideDate: String,
        val openTime: String? = null,
        val closeTime: String? = null,
        val isClosed: Boolean = false,
        val reason: String? = null,
    ) {
        fun validate() {
            require(overrideDate.isNotBlank()) { "overrideDate is required" }
            if (isClosed) {
                // openTime/closeTime optional when closed
            } else {
                require(!openTime.isNullOrBlank()) { "openTime required when not closed" }
                require(!closeTime.isNullOrBlank()) { "closeTime required when not closed" }
            }
            reason?.let {
                require(it.length <= 200) { "Reason must not exceed 200 characters" }
            }
        }
    }

    @Serializable
    data class UpdateOverrideRequestDto(
        val openTime: String? = null,
        val closeTime: String? = null,
        val isClosed: Boolean? = null,
        val reason: String? = null,
    ) {
        fun validate() {
            reason?.let {
                require(it.length <= 200) { "Reason must not exceed 200 characters" }
            }
        }
    }
}
