package org.example.project.core.domain.enums.employee

import kotlinx.serialization.Serializable

@Serializable
enum class ContractedHoursPeriod {
    WEEKLY,
    MONTHLY,
    ;

    companion object {
        /** Returns the number of weeks in a specific month (days / 7) */
        fun weeksInMonth(
            year: Int,
            month: Int,
        ): Double = daysInMonth(year, month) / 7.0

        fun daysInMonth(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
                else -> 30
            }

        fun fromString(value: String?): ContractedHoursPeriod? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
