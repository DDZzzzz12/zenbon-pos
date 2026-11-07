package org.example.project.core.security

import io.ktor.server.application.*

/**
 * Interface for resolving JWT token claims from an ApplicationCall.
 *
 * Implemented by pos-server's Security module which handles the full JWT
 * validation pipeline including development mode bypass, temporary sessions,
 * and token blacklisting. Feature modules depend on this interface
 * to access authenticated user information without coupling to the
 * security infrastructure.
 *
 * Registered as a singleton in Koin DI.
 */
interface TokenClaimsProvider {
    /**
     * Extracts and validates token claims from the current request.
     *
     * @param call The current Ktor ApplicationCall
     * @return TokenClaims if authentication is valid, null otherwise
     */
    suspend fun getTokenClaims(call: ApplicationCall): TokenClaims?
}
