package org.example.project.infrastructure.security

import org.example.project.core.domain.enums.auth.Capability

/**
 * What a route requires of its caller.
 *
 * Stage 1 reduced this from six arms to three. `Role`, `AnyRole` and `PermissionOrRole` are gone,
 * replaced by [Cap]; `Authenticated` is gone because "any logged-in principal" turned out to mean
 * "including a terminal", which is defect D4. Their pre-migration definitions are preserved for the
 * equivalence baseline as `LegacyAccessLevel` in the test source set.
 *
 * **Stage 1c removed the last arm that could bypass the gate.** `Custom` told the plugin to skip
 * enforcement entirely and trust the handler, and three of its eight handlers turned out not to
 * check anything. Every arm now enforces something centrally, so "this route is not gated" is no
 * longer expressible.
 */
sealed class AccessLevel {
    /** No authentication required (login, health check, public resources). */
    data object Public : AccessLevel()

    /**
     * Requires exactly one [Capability].
     *
     * Carries no exact-match trap, unlike the `Role` arm it replaced: a capability either is or is
     * not in the principal's resolved set, so there is no way to write a rule that a *more*
     * privileged principal fails (defect D1).
     */
    data class Cap(
        val capability: Capability,
    ) : AccessLevel()

    /**
     * Requires **any one** of [anyOf], and the handler additionally proves ownership.
     *
     * For routes addressing a specific employee's row, where "may I see this?" depends on *whose*
     * row it is and the registry cannot know that. `GET /api/employees/{id}` is the shape: reading
     * your own record needs `SELF_READ`, reading a colleague's needs `EMPLOYEE_READ`, and only the
     * handler can tell which case it is.
     *
     * **A set, not a single capability, on purpose.** The floor is the weaker member in practice,
     * since `SELF_*` sits in every group — but writing both makes the rule state the whole policy,
     * and a group holding `EMPLOYEE_READ` without `SELF_READ` is admitted correctly rather than
     * being refused by a floor that happened to name only the self half.
     *
     * The floor is a *floor*, never the whole check: passing it means the caller may attempt the
     * route, not that they may see this row. [ownership] documents what the handler must still
     * prove, and `OwnershipFloorTest` asserts that every route carrying this arm actually does.
     */
    data class CapWithOwnership(
        val anyOf: Set<Capability>,
        val ownership: String,
    ) : AccessLevel() {
        init {
            require(anyOf.isNotEmpty()) {
                "CapWithOwnership with an empty capability set would admit nobody, which is a typo, " +
                    "not a policy — use Cap, or name the capabilities."
            }
        }
    }

    /**
     * Requires **every** capability in [allOf].
     *
     * For an operation that restructures two things a principal is separately trusted with. Merging
     * two business partners re-points a creditor role and a debtor role in one write, so holding
     * only one side must not be enough — see the business partner design spec, §4.
     *
     * Strictly narrowing, so it carries no exact-match trap: a *more* privileged principal always
     * still passes, which is the defect that removed the old `Role` arm.
     *
     * Two or more, enforced. A one-element `AllOf` is a [Cap] written the long way, and it reads at
     * the call site as though it demanded more than it does.
     */
    data class AllOf(
        val allOf: Set<Capability>,
    ) : AccessLevel() {
        init {
            require(allOf.size >= 2) {
                "AllOf names $allOf. Fewer than two capabilities is a Cap written the long way — " +
                    "use Cap, or name the second capability."
            }
        }
    }

    /**
     * Requires the config-defined maintainer account: a verified `tokenType=maintainer` JWT.
     *
     * **Not a capability, on purpose.** Capabilities resolve from an employee's permission group in
     * the database; the maintainer is not an employee. Modelling it as a `Capability` would need a
     * special case in the resolver, a catalogue entry in the permission-group editor, and a way for
     * an operator to *grant* log access to a group — none of which is wanted. No capability set an
     * employee could hold satisfies this arm.
     */
    data object Maintainer : AccessLevel()
}

/**
 * Every capability this level names — the whole set for [AccessLevel.CapWithOwnership] and
 * [AccessLevel.AllOf], one for [AccessLevel.Cap], none for [AccessLevel.Public].
 *
 * It says what a level NAMES, never how the names combine: `CapWithOwnership` needs any one of its
 * set and `AllOf` needs all of its set, and both report the same shape here. Reachability is decided
 * by [AccessDecision.check], not by this property — anything that compares a held set against this
 * set directly is asking "is any of this relevant", which is a coverage question, not a gate.
 *
 * Exists so nothing has to write `filterIsInstance<Cap>()`, which is what four separate registry
 * tests were doing when Stage 1c added an arm. That pattern does not fail when a new arm appears;
 * it silently stops covering it, and a consistency test that stops covering something looks exactly
 * like a consistency test that passes. A `when` over a sealed class fails to compile instead.
 */
val AccessLevel.requiredCapabilities: Set<Capability>
    get() =
        when (this) {
            is AccessLevel.Public -> emptySet()
            is AccessLevel.Cap -> setOf(capability)
            is AccessLevel.CapWithOwnership -> anyOf
            is AccessLevel.AllOf -> allOf
            is AccessLevel.Maintainer -> emptySet()
        }

/**
 * How this level reads on an admin screen — "PUBLIC", "CAPABILITY: X", "CAPABILITY (any of): …",
 * "CAPABILITY (all of): …".
 *
 * One definition, so `/api/admin/permissions` and the Stage 5c diagnostics describe the same rule
 * the same way. Two renderings of one rule is how a screen comes to say something the gate does not.
 */
val AccessLevel.description: String
    get() =
        when (this) {
            is AccessLevel.Public -> "PUBLIC"
            is AccessLevel.Cap -> "CAPABILITY: ${capability.name}"
            is AccessLevel.CapWithOwnership ->
                "CAPABILITY (any of): " +
                    anyOf.sortedBy { it.name }.joinToString { it.name } +
                    " + ownership: $ownership"
            is AccessLevel.AllOf ->
                "CAPABILITY (all of): " + allOf.sortedBy { it.name }.joinToString { it.name }
            is AccessLevel.Maintainer -> "MAINTAINER"
        }
