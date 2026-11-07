package org.example.project.admin.core.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Formats an ISO local datetime string to a compact dashboard display.
 * Returns the input unchanged if it cannot be parsed.
 */
fun formatDashboardDateTime(isoString: String): String =
    try {
        val dt = LocalDateTime.parse(isoString)
        val day =
            dt.date.day
                .toString()
                .padStart(2, '0')
        val month =
            dt.date.month.number
                .toString()
                .padStart(2, '0')
        val year = dt.date.year
        val hour = dt.hour.toString().padStart(2, '0')
        val minute = dt.minute.toString().padStart(2, '0')
        "$day.$month.$year $hour:$minute"
    } catch (e: Exception) {
        isoString
    }

/**
 * "21.10. 14:05" — day, month and time of an ISO local datetime, for list rows that sit under a
 * year-less heading. Returns the input unchanged if it cannot be parsed.
 */
fun formatDashboardShortDateTime(isoString: String): String =
    try {
        val dt = LocalDateTime.parse(isoString)
        val day =
            dt.date.day
                .toString()
                .padStart(2, '0')
        val month =
            dt.date.month.number
                .toString()
                .padStart(2, '0')
        val hour = dt.hour.toString().padStart(2, '0')
        val minute = dt.minute.toString().padStart(2, '0')
        "$day.$month. $hour:$minute"
    } catch (e: Exception) {
        isoString
    }

/**
 * Formats an ISO-8601 **instant** (`2026-09-01T02:00:00Z`) in the viewer's own time zone.
 *
 * [formatDashboardDateTime] cannot do this: it parses a `LocalDateTime`, which rejects the trailing
 * offset, so a server timestamp rendered through it comes out as the raw string. Returns the input
 * unchanged when it will not parse, matching the sibling above.
 */
fun formatAdminInstant(isoInstant: String): String =
    try {
        val local = Instant.parse(isoInstant).toLocalDateTime(TimeZone.currentSystemDefault())
        val day =
            local.date.day
                .toString()
                .padStart(2, '0')
        val month =
            local.date.month.number
                .toString()
                .padStart(2, '0')
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        "$day.$month.${local.date.year} $hour:$minute"
    } catch (e: Exception) {
        isoInstant
    }
