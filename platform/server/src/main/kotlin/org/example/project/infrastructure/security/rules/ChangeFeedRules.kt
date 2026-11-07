package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * The client sync change feed — `ChangeFeedRoutes.kt`.
 *
 * Carries `SYNC_READ`, deliberately not `AUDIT_READ`: every client polls this feed, whereas
 * the audit trail is manager-gated. Sharing one capability would have opened the audit trail
 * to every terminal.
 */
object ChangeFeedRules : RuleModule {
    override val name = "Change feed"

    override val pathPrefixes = listOf("/api/changes")

    override val allowedCapabilities = setOf(Capability.SYNC_READ)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/changes" to HttpMethod.Get) to Cap(Capability.SYNC_READ),
        )
}
