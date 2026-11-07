package org.example.project.features.audit.data.service

import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.example.project.features.audit.domain.repository.EntryLogRepository
import org.example.project.utils.dbQuery
import kotlin.time.Clock

class EntryLogRetentionJob(
    private val repository: EntryLogRepository,
) {
    suspend fun pruneOlderThanDays(days: Int): Int =
        dbQuery {
            val tz = TimeZone.currentSystemDefault()
            val cutoff =
                Clock.System
                    .now()
                    .minus(DateTimePeriod(days = days), tz)
                    .toLocalDateTime(tz)
            repository.deleteOlderThan(cutoff)
        }
}
