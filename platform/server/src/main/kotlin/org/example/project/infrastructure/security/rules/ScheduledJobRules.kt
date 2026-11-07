package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Scheduler administration — `ScheduledJobRoutes.kt`.
 */
object ScheduledJobRules : RuleModule {
    override val name = "Scheduled jobs"

    override val pathPrefixes = listOf("/api/scheduled-jobs")

    override val allowedCapabilities = setOf(Capability.SCHEDULED_JOB_READ, Capability.SCHEDULED_JOB_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/scheduled-jobs" to HttpMethod.Get) to Cap(Capability.SCHEDULED_JOB_READ),
            ("/api/scheduled-jobs/types" to HttpMethod.Get) to Cap(Capability.SCHEDULED_JOB_READ),
            ("/api/scheduled-jobs/{id}" to HttpMethod.Get) to Cap(Capability.SCHEDULED_JOB_READ),
            ("/api/scheduled-jobs/{id}/run" to HttpMethod.Post) to Cap(Capability.SCHEDULED_JOB_WRITE),
            ("/api/scheduled-jobs/{id}/enabled" to HttpMethod.Put) to Cap(Capability.SCHEDULED_JOB_WRITE),
            ("/api/scheduled-jobs/{id}/executions" to HttpMethod.Get) to Cap(Capability.SCHEDULED_JOB_READ),
        )
}
