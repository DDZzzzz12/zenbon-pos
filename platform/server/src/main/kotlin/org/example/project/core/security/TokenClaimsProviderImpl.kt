package org.example.project.core.security

import io.ktor.server.application.*
import org.example.project.infrastructure.plugins.getTokenClaims as securityGetTokenClaims

/**
 * Implementation of TokenClaimsProvider that delegates to the Security.kt
 * extension function for full JWT validation including development mode,
 * temporary sessions, and token blacklisting.
 */
class TokenClaimsProviderImpl : TokenClaimsProvider {
    override suspend fun getTokenClaims(call: ApplicationCall): TokenClaims? = call.securityGetTokenClaims()
}
