@file:OptIn(ExperimentalTime::class)

package org.example.project.core.utils

import kotlinx.datetime.*
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

/**
 * Calculates the duration between two LocalDateTime values.
 *
 * @param start The start LocalDateTime
 * @param end The end LocalDateTime
 * @param timeZone The timezone (default: system time zone)
 * @return Duration between start and end
 */
fun durationBetween(
    start: LocalDateTime,
    end: LocalDateTime,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Duration {
    val startInstant = start.toInstant(timeZone)
    val endInstant = end.toInstant(timeZone)
    return endInstant - startInstant
}

/**
 * Formats a Duration into a human-readable string.
 * Example: "1d 3h 45m", "2h 10m", "30m"
 */
fun Duration.formatHumanReadable(): String {
    val days = this.inWholeDays
    val hours = (this - days.toDuration(DurationUnit.DAYS)).inWholeHours
    val minutes = (this - days.toDuration(DurationUnit.DAYS) - hours.toDuration(DurationUnit.HOURS)).inWholeMinutes

    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0) append("${hours}h ")
        if (minutes > 0 || (days == 0L && hours == 0L)) append("${minutes}m")
    }.trim()
}

fun daysBetween(
    start: LocalDate,
    end: LocalDate,
): Long = start.daysUntil(end).toLong()

// extension function
fun LocalDate.minusDays(days: Int): LocalDate = this.plus(DatePeriod(days = -days))

fun LocalDate.minusYears(years: Int): LocalDate = this.plus(DatePeriod(years = -years))
