package org.example.project.core.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import org.example.project.core.domain.enums.auth.Capability

/**
 * The capability set the route-authorization plugin resolved for this call.
 *
 * **In `server-common`, not `pos-server`, because the handlers that need it are not all in
 * `pos-server`** — `backoffice-timetracking` owns two of the ownership-checked routes.
 *
 * The plugin resolves the caller's capabilities before routing and stashes them here. A handler
 * doing an ownership check reads them back instead of resolving again: no second database hit, no
 * `GroupCapabilityResolver` injected into a route, and — the part that matters — no second opinion
 * about what the caller holds. Two independent resolutions of the same question is how the client
 * and server hierarchies drifted apart in defect D2.
 */
val HELD_CAPABILITIES: AttributeKey<Set<Capability>> = AttributeKey("HeldCapabilities")

/**
 * What the plugin decided this caller holds, or the empty set if it never ran.
 *
 * Empty means **deny**, never "unknown, so allow". The plugin runs for every non-`Public` rule, so
 * an empty set here on a gated route means something is wrong; failing closed turns that into a 403
 * rather than a silent bypass — which is exactly the failure `Custom` used to institutionalise.
 */
fun ApplicationCall.heldCapabilities(): Set<Capability> = attributes.getOrNull(HELD_CAPABILITIES) ?: emptySet()

/** True when the caller holds [capability], per the plugin's resolution. */
fun ApplicationCall.holds(capability: Capability): Boolean = capability in heldCapabilities()

/**
 * The ownership half of an `AccessLevel.CapWithOwnership` rule: the caller is [targetEmployeeId], or
 * else holds [capability].
 *
 * Returns false rather than throwing so the caller controls the error shape — the existing handlers
 * throw a `PosException` with route-specific wording, and this must not flatten that.
 *
 * Compares the raw token claim against the raw path parameter, deliberately: both are the string
 * form of the same UUID, and parsing here would mean deciding what a malformed id means in an
 * authorization check. A path parameter that is not a UUID fails the comparison and falls through to
 * the capability test, which is the safe direction.
 */
suspend fun ApplicationCall.isSelfOrHolds(
    targetEmployeeId: String?,
    capability: Capability,
): Boolean {
    val claims = getTokenClaims() ?: return false
    if (targetEmployeeId != null && claims.employeeId == targetEmployeeId) return true
    return holds(capability)
}

/** The status an ownership failure answers with. Named so the six routes cannot drift apart. */
val OWNERSHIP_DENIED_STATUS: HttpStatusCode = HttpStatusCode.Forbidden
