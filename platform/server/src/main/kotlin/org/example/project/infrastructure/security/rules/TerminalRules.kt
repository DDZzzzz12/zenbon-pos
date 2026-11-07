package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.AccessLevel.Public
import org.example.project.infrastructure.security.RuleModule

/**
 * POS terminal pairing, registration and administration — `TerminalRoutes.kt`.
 *
 * A station reads its own registration and registers itself (`TERMINAL_READ`,
 * `TERMINAL_SELF_REGISTER`); the back office lists, pairs, revokes and TSE-registers every
 * station (`TERMINAL_MANAGE`) — including the full `GET /api/v1/terminals` list, which is a
 * read sitting on the back-office side of the split.
 *
 * `POST /pair` is `Public`: it is authenticated by a short-lived pairing code carried in the body,
 * not by a principal, so there is no capability to require and no token to read. The KDoc used to
 * call it `Custom`, which was never what the rule said — corrected in Stage 1c, when `Custom` was
 * removed altogether.
 *
 * `POST /session` is `Public` for the same reason: it is authenticated by the device token, and it
 * is the call that mints the station JWT every other capability rule is then checked against.
 */
object TerminalRules : RuleModule {
    override val name = "Terminals"

    override val pathPrefixes = listOf("/api/v1/terminals")

    override val allowedCapabilities = setOf(Capability.TERMINAL_MANAGE, Capability.TERMINAL_READ, Capability.TERMINAL_SELF_REGISTER)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/terminals/register" to HttpMethod.Post) to Cap(Capability.TERMINAL_SELF_REGISTER),
            ("/api/v1/terminals/me" to HttpMethod.Get) to Cap(Capability.TERMINAL_READ),
            ("/api/v1/terminals/me-by-device" to HttpMethod.Get) to Cap(Capability.TERMINAL_READ),
            ("/api/v1/terminals/sync-tse" to HttpMethod.Post) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/tse-pairing-status" to HttpMethod.Get) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals" to HttpMethod.Get) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals" to HttpMethod.Post) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/{id}" to HttpMethod.Put) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/{id}" to HttpMethod.Delete) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/{id}/tse-register" to HttpMethod.Post) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/{id}/tse-deregister" to HttpMethod.Post) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/{id}/reactivate" to HttpMethod.Post) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/root-ca" to HttpMethod.Get) to Public,
            ("/api/v1/terminals/pair" to HttpMethod.Post) to Public,
            // Authenticated by the device token (Ktor's device-principal provider), not a JWT —
            // like /pair there is no capability to require. The transactional ceiling still runs.
            ("/api/v1/terminals/session" to HttpMethod.Post) to Public,
            ("/api/v1/terminals/{id}/pairing-code" to HttpMethod.Post) to Cap(Capability.TERMINAL_MANAGE),
            ("/api/v1/terminals/{id}/revoke" to HttpMethod.Post) to Cap(Capability.TERMINAL_MANAGE),
        )
}
