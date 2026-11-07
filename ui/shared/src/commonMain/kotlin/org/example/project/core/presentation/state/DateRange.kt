/**
 * Shared date range options for filtering across features.
 *
 * This enum provides predefined date ranges for filtering data by date
 * with utility functions to convert to actual date pairs.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.presentation.state

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Date range options for statistics and data queries.
 *
 * Provides predefined date ranges commonly used for filtering
 * financial and operational data across the application.
 */
@OptIn(ExperimentalTime::class)
enum class DateRange {
    /** Today's date only */
    TODAY,

    /** Yesterday's date only */
    YESTERDAY,

    /** From start of current week (Monday) to today */
    THIS_WEEK,

    /** Previous week (Monday to Sunday) */
    LAST_WEEK,

    /** From start of current month to today */
    THIS_MONTH,

    /** Last 3 months from today */
    LAST_3_MONTH,

    /** Custom user-defined date range */
    CUSTOM,

    ;

    /**
     * Converts date range to start and end date pair.
     *
     * Calculates the actual start and end dates based on the
     * selected predefined range. For CUSTOM, returns today's date
     * as placeholder (should be overridden by custom values).
     *
     * @return Pair of start date and end date in ISO format (YYYY-MM-DD)
     */
    fun toDatePair(): Pair<String, String> {
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date

        return when (this) {
            TODAY -> {
                val dateStr = today.toString()
                Pair(dateStr, dateStr)
            }
            YESTERDAY -> {
                val yesterday = today.minus(1, kotlinx.datetime.DateTimeUnit.DAY)
                val dateStr = yesterday.toString()
                Pair(dateStr, dateStr)
            }
            THIS_WEEK -> {
                // Start of week (Monday)
                val dayOfWeek = today.dayOfWeek.ordinal // 0 = Monday
                val startOfWeek = today.minus(dayOfWeek, kotlinx.datetime.DateTimeUnit.DAY)
                Pair(startOfWeek.toString(), today.toString())
            }
            LAST_WEEK -> {
                val dayOfWeek = today.dayOfWeek.ordinal
                val startOfLastWeek = today.minus(dayOfWeek + 7, kotlinx.datetime.DateTimeUnit.DAY)
                val endOfLastWeek = today.minus(dayOfWeek + 1, kotlinx.datetime.DateTimeUnit.DAY)
                Pair(startOfLastWeek.toString(), endOfLastWeek.toString())
            }
            THIS_MONTH -> {
                val startOfMonth = LocalDate(today.year, today.month, 1)
                Pair(startOfMonth.toString(), today.toString())
            }
            LAST_3_MONTH -> {
                val threeMonthsAgo = today.minus(3, DateTimeUnit.MONTH)
                Pair(threeMonthsAgo.toString(), today.toString())
            }
            CUSTOM -> {
                // Default to today for custom (will be overridden)
                val dateStr = today.toString()
                Pair(dateStr, dateStr)
            }
        }
    }
}
