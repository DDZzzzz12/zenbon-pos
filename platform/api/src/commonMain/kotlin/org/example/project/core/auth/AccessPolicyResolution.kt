package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * How a client turns what it knows about a principal into the gate it shows them.
 *
 * **In `shared/`, and public, so both clients resolve identically.** composeApp reaches it through
 * `PermissionManager.accessPolicy`; adminApp through `TokenStore.accessPolicy`. Two copies of this
 * rule is precisely how the client and server hierarchies drifted apart in defect D2 — an ordinal
 * `minRole` comparison on one side, exact role matching with an ADMIN escape on the other.
 *
 * Precedence, highest first:
 *  1. **[temporary]** — what the server reports while a step-up session is active, read from
 *     `GET /api/auth/me`. It replaces [reported] rather than being merged with it, and that is
 *     correct **because the server has already done the merging**: since the step-up stage a session
 *     UNIONS the lent capabilities onto the operator's own and answers `/api/auth/me` from
 *     `heldCapabilities()`, so this value is the union.
 *
 *     Fill it from the server's answer and nothing else. Filling it with the *approving manager's*
 *     set — which is what elevation used to mean, when it swapped `employeeId` outright — would now
 *     show controls the server refuses and hide ones it allows. The narrowing half of that was a
 *     real defect: a step-up could take capabilities away, because the approver's own restrictions
 *     replaced the operator's.
 *  2. **[reported]** — what the server said this principal holds, from the login response or
 *     `GET /api/auth/me`. Authoritative, including where it is NARROWER than the role suggests: an
 *     accountant carries role `MANAGER` and group `ACCOUNTANT`, and the whole point of purpose-built
 *     groups is that those two legitimately disagree.
 *  3. **[effectiveRole]** — a fallback, and only for one question: what should a NEW client do
 *     against an OLD server that reports no capabilities?
 *
 * ### Null means unknown, never "holds nothing"
 *
 * The single most important thing here, and the one most likely to be "simplified" into a bug. A
 * server predating the capability field reports an empty set. A client that read that as an empty
 * *grant* would hide every control in the UI — indistinguishable, to the user, from a broken app.
 * So an absent report falls back to the role, reproducing today's behaviour, which is exactly right
 * for a server that still only has today's behaviour to offer.
 *
 * The fallback is deliberately NOT the primary mechanism the C1 plan sketched as
 * `RoleBackedAccessPolicy`. Making role-derivation the main path would reproduce the very divergence
 * purpose-built groups exist to express.
 *
 * A null [effectiveRole] is signed-out, and gates on nothing — that is the one case where "holds
 * nothing" is the right reading.
 */
fun resolveAccessPolicy(
    reported: Set<Capability>?,
    temporary: Set<Capability>?,
    effectiveRole: EmployeeRole?,
): AccessPolicy {
    val held = temporary ?: reported
    return if (held != null) {
        SetAccessPolicy(held)
    } else {
        SetAccessPolicy(effectiveRole?.let { CapabilityBundles.forRole(it) } ?: emptySet())
    }
}

/**
 * Map capability names from the wire onto the enum, dropping any this build does not know.
 *
 * Dropping unknowns is the point: a client meeting a server that has ADDED a capability must ignore
 * the new name rather than fail. An empty or absent list resolves to null — unknown — for the reason
 * above.
 */
fun Set<String>?.toCapabilitiesOrNull(): Set<Capability>? =
    this
        ?.takeIf { it.isNotEmpty() }
        ?.mapNotNull { name -> Capability.entries.firstOrNull { it.name == name } }
        ?.toSet()
