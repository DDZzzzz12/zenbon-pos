package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Closing an accounting month — `MonthlyCloseRoutes.kt`.
 *
 * `MONTHLY_CLOSE` is RESERVED: the close is irreversible for the period it covers.
 */
object MonthlyCloseRules : RuleModule {
    override val name = "Monthly close"

    override val pathPrefixes = listOf("/api/v1/monthly-close")

    override val allowedCapabilities = setOf(Capability.MONTHLY_CLOSE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/monthly-close" to HttpMethod.Get) to Cap(Capability.MONTHLY_CLOSE),
        )
}
