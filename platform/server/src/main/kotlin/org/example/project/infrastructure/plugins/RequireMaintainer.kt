package org.example.project.infrastructure.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.MaintainerClaims
import org.example.project.infrastructure.errors.PosException

/**
 * Claims of a verified maintainer token — written by [RouteAuthorizationPlugin] only for a
 * `Maintainer` rule, read by handlers through [requireMaintainer].
 */
val maintainerVerifiedKey: AttributeKey<MaintainerClaims> = AttributeKey("RouteAuthorization.maintainer")

/**
 * The verified maintainer for a `Maintainer` route, or 401. Defence in depth, the same idea as
 * `HELD_CAPABILITIES` for `CapWithOwnership`: a handler that forgot the gate still cannot act
 * without the plugin having verified a token.
 */
fun ApplicationCall.requireMaintainer(): MaintainerClaims =
    attributes.getOrNull(maintainerVerifiedKey)
        ?: throw PosException(
            PosErrorCode.AUTH_INVALID_CREDENTIALS,
            HttpStatusCode.Unauthorized,
            "Maintainer authentication required",
        )
