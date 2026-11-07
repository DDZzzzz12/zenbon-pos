package org.example.project.core.utils

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The wall-clock time (`HH:mm`, 24 h) a notification timestamp shows in [timeZone].
 *
 * Notification DTOs carry `timestamp` as a string. The server sends a UTC instant
 * (`Clock.System.now().toString()`, e.g. `2026-09-13T22:11:05.123Z`), which the client used to
 * cut `HH:mm` out of verbatim — UTC on a till that is not in UTC.
 *
 * Accepted, in order:
 * 1. all digits: epoch milliseconds, what `AuthNotificationManager` sent before it switched to ISO
 *    instants — kept so a till talking to an older server still reads the right time;
 * 2. an ISO-8601 instant with `Z` or an offset, converted to [timeZone];
 * 3. an ISO-8601 local date-time with no zone, already local, shown as is.
 *
 * Anything else yields an empty string: a row without a time beats one with a garbled time, and a
 * display helper must never throw.
 */
fun formatNotificationTime(
    timestamp: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val local = parseNotificationTimestamp(timestamp.trim(), timeZone) ?: return ""
    return formatHourMinute(local.hour, local.minute)
}

private fun parseNotificationTimestamp(
    timestamp: String,
    timeZone: TimeZone,
): LocalDateTime? {
    if (timestamp.isEmpty()) return null
    if (timestamp.all { it.isDigit() }) {
        val millis = timestamp.toLongOrNull() ?: return null
        return Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    }
    return runCatching { Instant.parse(timestamp).toLocalDateTime(timeZone) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(timestamp) }.getOrNull()
}
