package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * The training-mode report — `TrainingReportRoutes.kt`.
 *
 * Its own module rather than part of `StatisticsRules`: this route is `Role(MANAGER)` while every
 * /api/statistics route is `Authenticated`, so sharing one capability would have widened this one
 * to every terminal. The equivalence test caught exactly that when they were merged.
 */
object TrainingReportRules : RuleModule {
    override val name = "Training report"

    override val pathPrefixes = listOf("/api/v1/reports")

    override val allowedCapabilities = setOf(Capability.TRAINING_REPORT_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/reports/training" to HttpMethod.Get) to Cap(Capability.TRAINING_REPORT_READ),
        )
}
