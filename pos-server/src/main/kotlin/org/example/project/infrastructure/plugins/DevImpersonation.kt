package org.example.project.infrastructure.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.security.JwtConfig
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DevImpersonation")

/**
 * Header a developer sets to act as a given permission group: `X-Dev-Act-As: ACCOUNTANT`.
 *
 * Honoured **only** when `security.jwt.developmentMode` is on. In every other configuration it is
 * ignored completely — not rejected, ignored, so a stray header from a dev machine pointed at a real
 * server changes nothing.
 */
const val DEV_ACT_AS_HEADER = "X-Dev-Act-As"

/** Echoed on any response whose authorization was decided by impersonation, so it is visible. */
const val DEV_AUTH_HEADER = "X-Dev-Auth"

/**
 * Development-mode **impersonation** — deliberately not a bypass.
 *
 * ### Why not a bypass
 *
 * A bypass answers a question nobody asks: "does this work for a principal holding every
 * capability?" Worse, it makes every authorization check vacuous in precisely the environment where
 * most manual testing happens — turn it on and an accountant's sidebar shows all 25 items, so you
 * cannot tell a working gate from a broken one.
 *
 * Impersonation keeps the gate live and lets you choose the principal instead. "Show me the app as
 * an accountant" is the thing you actually want, and it needs no seeded accountant.
 *
 * ### What it replaces
 *
 * Dev mode used to have TWO seams that disagreed. `CallAuthorization`'s role helpers returned true
 * unconditionally, while the capability gate in [RouteAuthorizationPlugin] had no bypass at all —
 * so after Stage 1 moved ~181 routes onto `Cap`, the surviving bypass only affected handler-level
 * checks sitting *behind* a gate that still ran. The comment claimed it "bypasses ALL
 * authentication"; it did not.
 *
 * There is now one seam: this file. Authentication is still short-circuited by the `dev-bypass`
 * bearer provider (that part is genuinely a bypass, and is what lets you skip logging in);
 * authorization is not.
 */
fun ApplicationCall.devImpersonatedCapabilities(): Set<Capability>? {
    if (!JwtConfig.isDevelopmentMode) return null

    val requested = request.header(DEV_ACT_AS_HEADER)?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    val group = CapabilityBundles.definitions().firstOrNull { it.code.equals(requested, ignoreCase = true) }
    if (group == null) {
        // Deliberately loud and deliberately NOT fatal: a typo should send you back to your own
        // capabilities, never silently to somebody else's.
        logger.warn(
            "DEV: {} named '{}', which is not a seeded permission group. Known: {}. Ignoring.",
            DEV_ACT_AS_HEADER,
            requested,
            CapabilityBundles.definitions().joinToString { it.code },
        )
        return null
    }

    logger.warn("DEV: acting as group {} — this must never happen in production", group.code)
    response.header(DEV_AUTH_HEADER, "acting-as=${group.code}")
    return group.capabilities
}
