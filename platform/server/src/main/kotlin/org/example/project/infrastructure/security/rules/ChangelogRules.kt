package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Release notes served from `changelog_release` — `ChangelogRoutes.kt`.
 */
object ChangelogRules : RuleModule {
    override val name = "Changelog"

    override val pathPrefixes = listOf("/api/v1/changelog")

    override val allowedCapabilities = setOf(Capability.CHANGELOG_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/changelog" to HttpMethod.Get) to Cap(Capability.CHANGELOG_READ),
        )
}
