package org.example.project.features.scheduler.data.service

import org.example.project.core.domain.enums.scheduler.JobFrequency
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Pure, side-effect free computation of the next scheduled-run instant. */
class NextRunCalculator {
    /**
     * @param dayOfWeek 1..7, Mon=1. Required for WEEKLY.
     * @param dayOfMonth 1..28 or -1 for last-day-of-month. Required for MONTHLY.
     * @param reference reference instant (normally now).
     * @return next occurrence strictly AFTER reference (never equal).
     */
    fun next(
        frequency: JobFrequency,
        timeOfDay: String,
        dayOfWeek: Int?,
        dayOfMonth: Int?,
        zone: ZoneId,
        reference: Instant,
    ): Instant {
        val (hour, minute) = parseHHmm(timeOfDay)
        val refZdt = reference.atZone(zone)
        val time = LocalTime.of(hour, minute)

        return when (frequency) {
            JobFrequency.DAILY -> {
                val candidate = refZdt.with(time)
                if (candidate.isAfter(refZdt)) {
                    candidate.toInstant()
                } else {
                    candidate.plusDays(1).toInstant()
                }
            }
            JobFrequency.WEEKLY -> {
                require(dayOfWeek in 1..7) { "dayOfWeek must be 1..7 for WEEKLY" }
                val target = DayOfWeek.of(dayOfWeek!!)
                var candidate = refZdt.with(TemporalAdjusters.nextOrSame(target)).with(time)
                if (!candidate.isAfter(refZdt)) {
                    candidate = candidate.plusWeeks(1)
                }
                candidate.toInstant()
            }
            JobFrequency.MONTHLY -> {
                require(dayOfMonth == -1 || dayOfMonth in 1..28) {
                    "dayOfMonth must be -1 or 1..28 for MONTHLY"
                }
                var candidate = monthlyCandidate(refZdt, dayOfMonth!!, time)
                if (!candidate.isAfter(refZdt)) {
                    candidate = monthlyCandidate(refZdt.plusMonths(1), dayOfMonth, time)
                }
                candidate.toInstant()
            }
        }
    }

    private fun monthlyCandidate(
        basis: ZonedDateTime,
        dayOfMonth: Int,
        time: LocalTime,
    ): ZonedDateTime =
        if (dayOfMonth == -1) {
            basis.with(TemporalAdjusters.lastDayOfMonth()).with(time)
        } else {
            basis.withDayOfMonth(dayOfMonth).with(time)
        }

    private fun parseHHmm(s: String): Pair<Int, Int> {
        val parts = s.split(":")
        require(parts.size == 2) { "timeOfDay must be HH:MM, got: $s" }
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        require(h in 0..23 && m in 0..59) { "timeOfDay out of range: $s" }
        return h to m
    }
}
