package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Entry logs and the transaction audit trail — `EntryLogRoutes.kt`, `TransactionAuditRoutes.kt`.
 *
 * Manager-gated compliance evidence. See `ChangeFeedRules` for why the sync feed is separate.
 */
object AuditRules : RuleModule {
    override val name = "Audit"

    override val pathPrefixes = listOf("/api/entry-logs", "/api/audit")

    override val allowedCapabilities = setOf(Capability.AUDIT_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/entry-logs/paginated" to HttpMethod.Get) to Cap(Capability.AUDIT_READ),
            ("/api/audit/transactions" to HttpMethod.Get) to Cap(Capability.AUDIT_READ),
            ("/api/audit/transactions/paginated" to HttpMethod.Get) to Cap(Capability.AUDIT_READ),
        )
}
