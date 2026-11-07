package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Maintainer
import org.example.project.infrastructure.security.AccessLevel.Public
import org.example.project.infrastructure.security.RuleModule

/**
 * The config-defined maintainer account — `MaintenanceRoutes.kt`.
 *
 * Login is `Public` (it must work before a principal exists; the route itself answers 404 while the
 * feature is off). Everything else is [Maintainer]: no employee capability reaches it, and no
 * permission group can grant it.
 */
object MaintenanceRules : RuleModule {
    override val name = "Maintenance"

    override val pathPrefixes = listOf("/api/maintenance")

    override val allowedCapabilities: Set<Capability> = emptySet()

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/maintenance/login" to HttpMethod.Post) to Public,
            ("/api/maintenance/logs" to HttpMethod.Get) to Maintainer,
        )
}
