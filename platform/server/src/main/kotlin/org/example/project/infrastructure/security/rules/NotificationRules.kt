package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * The notification SSE stream — `NotificationStreamRoutes.kt`.
 */
object NotificationRules : RuleModule {
    override val name = "Notifications"

    override val pathPrefixes = listOf("/api/notifications")

    override val allowedCapabilities = setOf(Capability.NOTIFICATION_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/notifications/stream" to HttpMethod.Get) to Cap(Capability.NOTIFICATION_READ),
        )
}
