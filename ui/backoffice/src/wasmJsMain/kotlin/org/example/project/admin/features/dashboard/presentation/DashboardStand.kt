package org.example.project.admin.features.dashboard.presentation

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** The moment shown in the header's "Stand" line: the [date] of the last refresh and its [time] as `14:36`. */
data class DashboardStand(
    val date: LocalDate,
    val time: String,
)

/** The moment of the last refresh, in the viewer's own time zone. */
fun currentDashboardStand(): DashboardStand {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = now.hour.toString().padStart(2, '0')
    val minute = now.minute.toString().padStart(2, '0')
    return DashboardStand(date = now.date, time = "$hour:$minute")
}
