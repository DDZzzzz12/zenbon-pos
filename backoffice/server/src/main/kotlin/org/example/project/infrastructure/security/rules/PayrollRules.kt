package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Cost centres and wage types — `CostCenterRoutes.kt`, `WageTypeRoutes.kt`, and the
 * timetracking-plugin cost-centre proxy.
 *
 * Reads are `Authenticated` (the till offers a cost centre when clocking in) while every write
 * is `Role(ADMIN)`, so this pair straddles the widest gap in the vocabulary: `PAYROLL_READ` sits
 * in `TERMINAL_DEVICE`, `PAYROLL_WRITE` only in `ADMIN`.
 */
object PayrollRules : RuleModule {
    override val name = "Payroll master data"

    override val pathPrefixes = listOf("/api/costcenters", "/api/wagetypes", "/api/plugins")

    override val allowedCapabilities = setOf(Capability.PAYROLL_READ, Capability.PAYROLL_WRITE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/costcenters" to HttpMethod.Get) to Cap(Capability.PAYROLL_READ),
            ("/api/costcenters/{id}" to HttpMethod.Get) to Cap(Capability.PAYROLL_READ),
            ("/api/costcenters" to HttpMethod.Post) to Cap(Capability.PAYROLL_WRITE),
            ("/api/costcenters/{id}" to HttpMethod.Put) to Cap(Capability.PAYROLL_WRITE),
            ("/api/costcenters/{id}" to HttpMethod.Delete) to Cap(Capability.PAYROLL_WRITE),
            ("/api/plugins/timetracking-plugin/costcenters" to HttpMethod.Get) to Cap(Capability.PAYROLL_READ),
            ("/api/plugins/timetracking-plugin/costcenters/{id}" to HttpMethod.Get) to Cap(Capability.PAYROLL_READ),
            ("/api/plugins/timetracking-plugin/costcenters" to HttpMethod.Post) to Cap(Capability.PAYROLL_WRITE),
            ("/api/plugins/timetracking-plugin/costcenters/{id}" to HttpMethod.Put) to Cap(Capability.PAYROLL_WRITE),
            ("/api/plugins/timetracking-plugin/costcenters/{id}" to HttpMethod.Delete) to Cap(Capability.PAYROLL_WRITE),
            ("/api/wagetypes" to HttpMethod.Get) to Cap(Capability.PAYROLL_READ),
            ("/api/wagetypes/{id}" to HttpMethod.Get) to Cap(Capability.PAYROLL_READ),
            ("/api/wagetypes" to HttpMethod.Post) to Cap(Capability.PAYROLL_WRITE),
            ("/api/wagetypes/{id}" to HttpMethod.Put) to Cap(Capability.PAYROLL_WRITE),
            ("/api/wagetypes/{id}" to HttpMethod.Delete) to Cap(Capability.PAYROLL_WRITE),
        )
}
