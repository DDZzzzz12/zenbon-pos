package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.Public
import org.example.project.infrastructure.security.RuleModule

/**
 * First-run setup — `InitialSetupRoutes.kt`, plus the client-error report endpoint.
 *
 * `status`, `initialize` and `restore-backup` are `Public` and stay so: they must work before
 * any employee exists. `readiness` is deliberately NOT public — it reports fiscal terminal and
 * TSE commissioning state — so it carries `SETUP_READ`.
 */
object SetupRules : RuleModule {
    override val name = "Setup"

    override val pathPrefixes = listOf("/api/setup", "/api/v1/client-errors")

    override val allowedCapabilities = setOf(Capability.SETUP_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/setup/status" to HttpMethod.Get) to Public,
            ("/api/setup/readiness" to HttpMethod.Get) to Cap(Capability.SETUP_READ),
            ("/api/setup/initialize" to HttpMethod.Post) to Public,
            ("/api/setup/restore-backup" to HttpMethod.Post) to Public,
            ("/api/v1/client-errors" to HttpMethod.Post) to Public,
        )
}
