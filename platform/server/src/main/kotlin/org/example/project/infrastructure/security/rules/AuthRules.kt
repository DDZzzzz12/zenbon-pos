package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.CapWithOwnership
import org.example.project.infrastructure.security.AccessLevel.Public
import org.example.project.infrastructure.security.RuleModule

/**
 * Login, session lifecycle, manager approvals and temporary sessions — `AuthRoutes.kt`,
 * `SessionAdminRoutes.kt`, `ApprovalRoutes.kt`, `TemporarySessionRoutes.kt`.
 *
 * Four access levels share this one prefix, which is why the defaults do little work here:
 *  - `login` and `refresh` stay `Public` — they must work before a principal exists;
 *  - checking your own token, logging out, reading your own approval status and starting a
 *    temporary session are `SELF_*`, reachable by every role;
 *  - granting or denying someone else's approval request is `SESSION_WRITE`, manager-gated;
 *  - editing session policy and revoking every session shop-wide are `Role(ADMIN)`, so they
 *    carry `SESSION_REVOKE` rather than `SESSION_WRITE`.
 *
 * `GET /api/auth/sessions/policy` is `SELF_READ`, not `SESSION_READ`: every client reads the
 * policy to know its own timeout, while only an admin may change it.
 */
object AuthRules : RuleModule {
    override val name = "Auth"

    override val pathPrefixes = listOf("/api/auth")

    override val allowedCapabilities =
        setOf(
            Capability.ORDER_WRITE,
            Capability.SELF_READ,
            Capability.SELF_WRITE,
            Capability.SESSION_READ,
            Capability.SESSION_REVOKE,
            Capability.SESSION_WRITE,
        )

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/auth/login" to HttpMethod.Post) to Public,
            ("/api/auth/refresh" to HttpMethod.Post) to Public,
            ("/api/auth/validate" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/auth/logout" to HttpMethod.Post) to Cap(Capability.SELF_WRITE),
            ("/api/auth/me" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/auth/sessions/policy" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/auth/sessions/policy" to HttpMethod.Put) to Cap(Capability.SESSION_REVOKE),
            ("/api/auth/sessions/revoke-all" to HttpMethod.Post) to Cap(Capability.SESSION_REVOKE),
            ("/api/auth/approvals/pending" to HttpMethod.Get) to Cap(Capability.SESSION_READ),
            ("/api/auth/approvals/{requestId}/approve" to HttpMethod.Post) to Cap(Capability.SESSION_WRITE),
            ("/api/auth/approvals/{requestId}/deny" to HttpMethod.Post) to Cap(Capability.SESSION_WRITE),
            ("/api/auth/approvals/{requestId}/status" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/auth/approvals/{requestId}" to HttpMethod.Delete) to Cap(Capability.SESSION_WRITE),
            // A step-up is always lent to the CALLER (the requester comes from the token, never the
            // body), and only that caller may extend or end it — the ownership the arm documents.
            // ORDER_WRITE is the order station's way in (owner decision 2026-10-03, emu21 NOTE-v:
            // a manager signs in temporarily on the station to Storno/transfer). The station mask
            // carries no SELF_WRITE — so it cannot change the shared account's PIN or profile — and
            // every person who steps up holds SELF_WRITE, so nobody else's reach changes.
            ("/api/auth/temporary-session" to HttpMethod.Post) to
                CapWithOwnership(setOf(Capability.SELF_WRITE, Capability.ORDER_WRITE), STEP_UP_OWNERSHIP),
            ("/api/auth/temporary-session/active" to HttpMethod.Get) to Cap(Capability.SELF_READ),
            ("/api/auth/temporary-session/{sessionId}/extend" to HttpMethod.Put) to
                CapWithOwnership(setOf(Capability.SELF_WRITE, Capability.ORDER_WRITE), STEP_UP_OWNERSHIP),
            ("/api/auth/temporary-session/{sessionId}" to HttpMethod.Delete) to
                CapWithOwnership(setOf(Capability.SELF_WRITE, Capability.ORDER_WRITE), STEP_UP_OWNERSHIP),
        )

    private const val STEP_UP_OWNERSHIP =
        "lent to the caller's own employee id; extend/end only by the employee it was lent to"
}
