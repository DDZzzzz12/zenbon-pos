package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Custom exports — `CustomExportRoutes.kt`.
 *
 * Two tiers, and the split is deliberate. `CUSTOM_EXPORT_MANAGE` is who may *define* a shop's
 * exports; `CUSTOM_EXPORT_READ` is who may see the catalogue, list what is defined and run it.
 *
 * **Running is `_READ`, not `_MANAGE`.** That reads generous until you know what the run does: it
 * re-resolves every column of the stored definition against the *runner's* own capabilities and
 * refuses the whole file if one is not covered — it does not blank the column, and it does not skip
 * it. So a reader running a definition an accountant saved reaches exactly the data they could
 * already read on the screen for it, and nothing more. Gating the run on `_MANAGE` instead would buy
 * no protection the field check does not already give, while making the monthly extract a thing only
 * its author can produce.
 *
 * Neither capability admits anyone to data on its own. They gate admission to the *feature*; the
 * catalogue then filters field by field against the rest of what the caller holds — today every
 * catalogue field requires `CREDITOR_READ` — so exporting a column is never an easier way to read it
 * than the screen that shows it.
 *
 * `/api/custom-exports/catalogue` is a literal path and lands in the registry's exact map, which
 * `RoutePermissions.findRule` consults before it pattern-matches anything — so `/api/custom-exports/{id}`
 * cannot swallow it. The hedge is not needed anyway: the two carry different methods (`GET` versus
 * `DELETE`) and `ParameterizedRule.matches` rejects on method before it compares a single segment,
 * so even with the exact map consulted second the literal would still be the only rule that matches.
 */
object CustomExportRules : RuleModule {
    override val name = "Custom exports"

    override val pathPrefixes = listOf("/api/custom-exports")

    override val allowedCapabilities =
        setOf(Capability.CUSTOM_EXPORT_READ, Capability.CUSTOM_EXPORT_MANAGE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/custom-exports/catalogue" to HttpMethod.Get) to Cap(Capability.CUSTOM_EXPORT_READ),
            ("/api/custom-exports" to HttpMethod.Get) to Cap(Capability.CUSTOM_EXPORT_READ),
            ("/api/custom-exports/{id}/run" to HttpMethod.Get) to Cap(Capability.CUSTOM_EXPORT_READ),
            ("/api/custom-exports" to HttpMethod.Post) to Cap(Capability.CUSTOM_EXPORT_MANAGE),
            ("/api/custom-exports/{id}" to HttpMethod.Put) to Cap(Capability.CUSTOM_EXPORT_MANAGE),
            ("/api/custom-exports/{id}" to HttpMethod.Delete) to Cap(Capability.CUSTOM_EXPORT_MANAGE),
        )
}
