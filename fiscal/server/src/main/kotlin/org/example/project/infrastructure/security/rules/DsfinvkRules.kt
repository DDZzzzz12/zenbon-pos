package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * The DSFinV-K export — `DsfinvkExportRoutes.kt`.
 *
 * Deliberately separate from `DATEV_EXPORT`: one is the Kassenpruefung artefact, the other the
 * accountant handoff, and an auditor will ask which was performed.
 */
object DsfinvkRules : RuleModule {
    override val name = "DSFinV-K export"

    override val pathPrefixes = listOf("/api/v1/dsfinvk")

    override val allowedCapabilities = setOf(Capability.DSFINVK_EXPORT)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/dsfinvk/export" to HttpMethod.Post) to Cap(Capability.DSFINVK_EXPORT),
            ("/api/v1/dsfinvk/validate" to HttpMethod.Get) to Cap(Capability.DSFINVK_EXPORT),
            ("/api/v1/dsfinvk/statistics" to HttpMethod.Get) to Cap(Capability.DSFINVK_EXPORT),
        )
}
