package org.example.project.infrastructure.plugins

import io.ktor.util.AttributeKey
import org.example.project.core.security.JwtConfig
import org.example.project.core.security.TokenClaims

/**
 * Claims already decoded and verified by [RouteAuthorizationPlugin] during the pre-routing `onCall`
 * phase, so a route handler need not verify the same token again.
 *
 * Only ever written after a successful [JwtConfig.validateToken], so reading it is equivalent to
 * re-verifying — never a way to bypass verification.
 */
val verifiedClaimsKey: AttributeKey<TokenClaims> = AttributeKey("RouteAuthorization.verifiedClaims")
