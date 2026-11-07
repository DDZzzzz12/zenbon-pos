package org.example.project.core.domain.dto.employee

import kotlinx.serialization.Serializable

/**
 * The answers to "what can this group actually do?" and "why can't this person do X?".
 *
 * Both questions are currently answered by reading Kotlin — `CapabilityBundles`, the rule modules
 * and the plugin — which is not something an operator, or a support call, can do. The migrations
 * have since made the database the authority, so the compiled sets are no longer even the right
 * place to look: a group edited through the Stage 5 editor is invisible in the source.
 */
object PermissionDiagnosticsDto {
    /**
     * What one group reaches, optionally as seen from one class of station.
     *
     * [identityCapabilities] is what the group confers; [effectiveCapabilities] is what survives the
     * station mask. They differ only for a login-less station — a person's request always arrives
     * `UNBOUND`, whose mask is the full set. Reporting both is the point: "the group has it but this
     * station does not" is exactly the confusion the station axis introduced.
     */
    @Serializable
    data class EffectiveAccessDto(
        val groupCode: String,
        val station: String,
        val identityCapabilities: Set<String>,
        val effectiveCapabilities: Set<String>,
        val maskedOut: Set<String>,
        val reachable: List<RouteAccessDto>,
        val unreachable: List<RouteAccessDto>,
    )

    /** One route, and whether the group in question gets through it. */
    @Serializable
    data class RouteAccessDto(
        val path: String,
        val method: String,
        val requires: String,
        val missing: Set<String> = emptySet(),
    )

    /**
     * Why one employee is or is not admitted to one route.
     *
     * [ruleFound] false means the route carries no rule at all and is therefore **denied by
     * default** — the single most confusing failure this system produces, because the route exists,
     * returns 403, and nothing in the group's capability list explains it.
     *
     * [ownershipApplies] flags that passing this check is only a floor: the handler still decides
     * whether the caller may see *that particular row*, so an allowed verdict here does not promise
     * a 200.
     */
    @Serializable
    data class AccessExplanationDto(
        val employeeId: String,
        val groupCode: String?,
        val path: String,
        val method: String,
        val ruleFound: Boolean,
        val requires: String?,
        val allowed: Boolean,
        val reason: String,
        val missing: Set<String> = emptySet(),
        val ownershipApplies: Boolean = false,
        val caveat: String? = null,
    )
}
