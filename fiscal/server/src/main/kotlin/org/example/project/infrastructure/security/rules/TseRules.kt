package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * TSE commissioning, self-test, exports, firmware and decommissioning — `TseAdminRoutes.kt`.
 *
 * Every rule was `Role(ADMIN)` and stays admin-only. Both `TSE_ADMIN` and `TSE_QUEUE_ADMIN` are
 * RESERVED, so Stage 5 will refuse to place either in an operator-created group.
 *
 * The signing queue keeps its own capability — pausing or cancelling it changes whether
 * transactions get signed at all, which is a different concern from commissioning the device — but
 * shares this module: `/api/tse/queue` nests inside `/api/tse`, and two modules declaring nested
 * prefixes makes ownership ambiguous, which `RuleModuleConsistencyTest` rejects.
 *
 * This module touches only the route registry; the `pos-tse` module itself is untouched.
 */
object TseRules : RuleModule {
    override val name = "TSE administration"

    override val pathPrefixes = listOf("/api/tse")

    override val allowedCapabilities = setOf(Capability.TSE_ADMIN, Capability.TSE_QUEUE_ADMIN)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/tse/queue/status" to HttpMethod.Get) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/entries" to HttpMethod.Get) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/entries/{id}" to HttpMethod.Get) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/entries/{id}/retry" to HttpMethod.Post) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/entries/{id}/cancel" to HttpMethod.Post) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/pause" to HttpMethod.Post) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/resume" to HttpMethod.Post) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/trigger" to HttpMethod.Post) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/queue/cleanup" to HttpMethod.Post) to Cap(Capability.TSE_QUEUE_ADMIN),
            ("/api/tse/config" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/config" to HttpMethod.Put) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/status" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/info" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/health" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/flashhealth" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/ping" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/selftest" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/updatetime" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/export/full" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/export/incremental" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/export/time-filtered" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/export/transaction-filtered" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/export/size" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/export" to HttpMethod.Delete) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/archives" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/verify" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/findings" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/explanations" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/setup" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/factory-reset" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/restart" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/decommission" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/csp-export/disable" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/entries/browse" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/export/lcm" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/firmware-check" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/firmware-apply-bundled" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/clients" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/transactions" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/last-transaction" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/admin/unlock" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/admin/lock" to HttpMethod.Post) to Cap(Capability.TSE_ADMIN),
            ("/api/tse/admin/status" to HttpMethod.Get) to Cap(Capability.TSE_ADMIN),
        )
}
