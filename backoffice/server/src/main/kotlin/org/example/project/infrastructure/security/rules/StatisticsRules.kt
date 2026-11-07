package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Sales, financial, catalog and promotion statistics — `StatisticsRoutes.kt`.
 *
 * All `Authenticated` before the migration, so `REPORT_READ` joins the `TERMINAL_DEVICE` bundle:
 * a terminal login can read the day''s revenue. That is defect D4 at its most visible, and the
 * first thing Stage 2 removes.
 */
object StatisticsRules : RuleModule {
    override val name = "Statistics"

    override val pathPrefixes = listOf("/api/statistics")

    override val allowedCapabilities = setOf(Capability.REPORT_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/statistics/sales/overview" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/trends" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/bills" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/by-table" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/by-table/{tableId}/sessions" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/by-employee" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/payments" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/canceled-items" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/sales/sessions" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/financial" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/catalog" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
            ("/api/statistics/promotions" to HttpMethod.Get) to Cap(Capability.REPORT_READ),
        )
}
