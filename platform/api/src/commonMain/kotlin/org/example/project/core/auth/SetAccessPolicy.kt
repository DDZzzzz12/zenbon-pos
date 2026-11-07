package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability

/**
 * An [AccessPolicy] backed by an already-resolved capability set.
 *
 * This is the terminal representation both sides converge on: the server resolves a principal's
 * group plus per-employee grants into a set, and the client receives the same set from
 * `GET /api/auth/me`. Nothing here knows about roles, groups or terminals — resolution happens
 * before construction.
 */
class SetAccessPolicy(
    private val capabilities: Set<Capability>,
) : AccessPolicy {
    override fun allows(capability: Capability): Boolean = capability in capabilities
}
