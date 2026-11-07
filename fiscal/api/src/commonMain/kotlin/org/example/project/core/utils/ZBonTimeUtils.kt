package org.example.project.core.utils

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Advances an ISO-8601 timestamp string by one second.
 *
 * Used when a Z-Bon timestamp serves as the START of a "since" / "from" statistics
 * range and needs to be strictly-exclusive so that the Z-Bon's own attributed bills
 * are not included in the range. The server-side sales overview summary filters by
 * Z-Bon-attributed time with an inclusive >= start, so advancing the start by one
 * second ensures bills attributed to the Z-Bon itself are excluded.
 */
fun advanceTimestampByOneSecond(isoTimestamp: String): String {
    val dt = LocalDateTime.parse(isoTimestamp)
    val tz = TimeZone.currentSystemDefault()
    val nextSecond = dt.toInstant(tz).epochSeconds + 1
    return Instant.fromEpochSeconds(nextSecond).toLocalDateTime(tz).toString()
}

/**
 * Get start time from last Z-Bon for date range calculations.
 *
 * @return Start time string in ISO format; defaults to start of today if no Z-Bon exists
 */
suspend fun getZBonStartTime(api: ZBonApiClient): String {
    var startTime: String? = null
    var failed = false
    runCatchingApi { api.getLatestZBonEndTime() }
        .onSuccess { time -> startTime = time }
        .onError { failed = true }
    if (failed) throw Exception("Error getting start time")
    startTime?.let { return advanceTimestampByOneSecond(it) }
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return LocalDateTime(now.date, LocalTime(0, 0)).toString()
}
