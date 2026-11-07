package org.example.project.infrastructure.security

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel.AllOf
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.CapWithOwnership
import org.example.project.infrastructure.security.AccessLevel.Maintainer
import org.example.project.infrastructure.security.AccessLevel.Public

/**
 * Whether a capability set satisfies an [AccessLevel] — the one comparison the gate makes.
 *
 * Extracted in Stage 5c so `/api/admin/permissions/explain` answers with the *same* code the
 * `RouteAuthorizationPlugin` enforces with, rather than a second implementation of the same rule.
 * A diagnostic that disagrees with the gate is worse than no diagnostic: it is consulted precisely
 * when somebody is confused, and it would confirm the wrong answer with authority.
 *
 * Deliberately excludes everything the gate does *around* this comparison — resolving the group,
 * applying the station mask, the transactional ceiling, ownership. Those need a request, a database
 * and a device, and folding them in would make this untestable in isolation and unusable from a
 * diagnostic. What it covers is the part that is pure, and the part that would silently drift.
 */
object AccessDecision {
    /**
     * Why a call was refused.
     *
     * [reason] is the message the gate raises verbatim, so the explain endpoint reports the text an
     * operator would actually have seen in the response, not a paraphrase of it.
     */
    data class Denial(
        val reason: String,
        val missing: Set<Capability>,
    )

    /**
     * Null when [held] satisfies [access], a [Denial] otherwise.
     *
     * [Public] is always satisfied — including by the empty set, which is what an unauthenticated
     * caller has.
     */
    fun check(
        access: AccessLevel,
        held: Set<Capability>,
    ): Denial? =
        when (access) {
            is Public -> null
            is Cap ->
                if (access.capability in held) {
                    null
                } else {
                    Denial("Required capability: ${access.capability.name}", setOf(access.capability))
                }
            is CapWithOwnership ->
                // The FLOOR only. Passing it means the caller may attempt the route, not that they
                // may see this row — the handler decides that, and `OwnershipFloorTest` asserts every
                // such handler actually does.
                if (access.anyOf.any { it in held }) {
                    null
                } else {
                    Denial(
                        "Required one of: " + access.anyOf.sortedBy { it.name }.joinToString { it.name },
                        access.anyOf,
                    )
                }
            is AllOf -> {
                // EVERY one, not any one — the opposite of the arm directly above, and the whole
                // reason this arm exists. Merging a creditor into a debtor restructures both, so a
                // bookkeeper trusted with one half must not be able to do it alone.
                //
                // `missing` names only what the caller lacks rather than the whole set: with an
                // all-of rule that is the actionable half, and repeating the capabilities they
                // already hold is what makes a 403 message read as if nothing they have counts.
                val missing = access.allOf - held
                if (missing.isEmpty()) {
                    null
                } else {
                    Denial(
                        "Required all of: " + access.allOf.sortedBy { it.name }.joinToString { it.name } +
                            " (missing: " + missing.sortedBy { it.name }.joinToString { it.name } + ")",
                        missing,
                    )
                }
            }
            is Maintainer ->
                // Never satisfiable by an employee's capability set: the gate for this arm is a verified
                // maintainer token, decided by RouteAuthorizationPlugin before this function is reached.
                // `/api/admin/permissions/explain` lands here when asked about a maintenance route.
                Denial("Requires the maintainer account, which no permission group can grant", emptySet())
        }
}
