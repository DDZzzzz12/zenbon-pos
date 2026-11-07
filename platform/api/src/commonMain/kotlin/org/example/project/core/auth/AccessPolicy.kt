package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability

/**
 * The single question every authorization decision reduces to, on both server and client.
 *
 * **Deliberately exposes no role.** A call site that can read the role will eventually branch on
 * it, which is how composeApp and adminApp ended up with three disagreeing gating models — an
 * ordinal `minRole` comparison on the client, exact role matching with an ADMIN escape on the
 * server, and six ad-hoc `can*` extensions in the admin console. Capability is the only currency.
 *
 * Server and client resolve through the same implementation, so CLAUDE.md rule 8 (guard at both
 * levels) holds by construction rather than by discipline.
 *
 * @see SetAccessPolicy
 */
interface AccessPolicy {
    /** Whether the principal holds [capability]. */
    fun allows(capability: Capability): Boolean

    /**
     * Whether the principal holds at least one of [capabilities].
     *
     * Fails closed on an empty argument list — a gate built from a dynamically assembled list must
     * not open just because the list came out empty.
     */
    fun allowsAny(vararg capabilities: Capability): Boolean = capabilities.any { allows(it) }

    /**
     * Whether the principal holds every one of [capabilities].
     *
     * Returns true for an empty argument list (vacuous truth), unlike [allowsAny]. The asymmetry is
     * intentional and tested.
     */
    fun allowsAll(vararg capabilities: Capability): Boolean = capabilities.all { allows(it) }
}
