package org.example.project.infrastructure.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.security.JwtConfig
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Security")

private const val DEFAULT_INSECURE_SECRET = "your-jwt-secret-key-change-in-production"

/**
 * Configures authentication for the POS system: JWT bearer auth, the login-less device-token
 * provider, and (development only) an auth bypass.
 *
 * - `auth-jwt`: standard JWT bearer auth via [JwtConfig]. Additionally rejects any token issued
 *   before the effective session cutoff — a stateless replacement for an in-memory blacklist.
 * - `device-principal`: login-less order-station auth via the `X-Device-Token` header
 *   (see [DeviceTokenAuthenticationProvider]).
 * - `dev-bypass`: DEVELOPMENT ONLY — accepts `Authorization: Bearer dev-bypass` and skips all
 *   authentication. Only registered when [JwtConfig.isDevelopmentMode] is true.
 */
fun Application.configureSecurity() {
    logger.info("Configuring security with JWT authentication")

    install(Authentication) {
        jwt("auth-jwt") {
            realm = JwtConfig.getJwtRealm()
            verifier(JwtConfig.verifier)

            validate { credential ->
                // A maintainer token is signed with our key but is not an employee: it must never
                // become a JWTPrincipal on an authenticate("auth-jwt") route. (It carries no
                // employeeNumber, so the check below would refuse it anyway; this makes that a
                // stated rule instead of an accident of claim names.)
                if (credential.payload.getClaim("tokenType").asString() == JwtConfig.MAINTAINER_TOKEN_TYPE) {
                    return@validate null
                }
                val employeeNumber = credential.payload.getClaim("employeeNumber").asString()
                if (employeeNumber != null) {
                    // Reject any access token issued before the effective session cutoff.
                    // This replaces the old in-memory blacklist with a stateless "session
                    // cutoff" check: tokens whose issued-at (iat) predate the cutoff are denied.
                    val iatEpoch =
                        credential.payload.issuedAt
                            ?.toInstant()
                            ?.epochSecond ?: 0L
                    val cutoff = ShopConfigurationManager.effectiveSessionCutoffEpochSeconds()
                    if (iatEpoch < cutoff) {
                        logger.debug("Rejected token issued before session cutoff: employee=$employeeNumber")
                        return@validate null
                    }

                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }

            challenge { _, _ ->
                if (JwtConfig.isDevelopmentMode) {
                    logger.debug("JWT validation failed, but development mode is enabled")
                } else {
                    val authHeader = call.request.headers["Authorization"]
                    val token = JwtConfig.extractBearerToken(authHeader)

                    if (token != null && JwtConfig.isTokenExpired(token)) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf(
                                "error" to "token_expired",
                                "message" to "Access token has expired",
                                "action" to "refresh_required",
                            ),
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf(
                                "error" to "invalid_token",
                                "message" to "Invalid authentication token",
                            ),
                        )
                    }
                }
            }
        }

        // Login-less order station credential (Terminal Phase A4, Task 5): the token lives in
        // the X-Device-Token header, not Authorization, so this is a custom provider — see
        // DeviceTokenAuthenticationProvider (DeviceTokenAuth.kt). Used via authenticate("auth-jwt",
        // "device-principal") on POST /api/orders only (OrderRoutes.kt) — the terminal-capability
        // ceiling in RouteAuthorizationPlugin already refused an unpaired/non-fiscal/unregistered
        // station before this provider ever runs.
        val capabilityResolver = this@configureSecurity.getKoin().get<TerminalCapabilityResolver>()
        deviceToken("device-principal") {
            resolveCapability = { tokenHash -> capabilityResolver.byTokenHash(tokenHash) }
        }

        // DEVELOPMENT ONLY: Create bypass authentication for easier testing
        // Only active when security.jwt.developmentMode: true in configuration
        // SECURITY WARNING: This bypasses ALL authentication - never enable in production
        if (JwtConfig.isDevelopmentMode) {
            logger.info("SECURITY WARNING: Development mode enabled - JWT bypass is active")

            bearer("dev-bypass") {
                authenticate { tokenCredential ->
                    if (tokenCredential.token == "dev-bypass") {
                        logger.debug("Development bypass authenticated - SECURITY: This should not happen in production")
                        UserIdPrincipal("dev-user")
                    } else {
                        null
                    }
                }
            }
        }
    }

    logger.info("Security configuration completed. Development mode: ${JwtConfig.isDevelopmentMode}")
}

/**
 * Fails fast on insecure auth configuration. Call once at startup before serving.
 */
fun assertSecureAuthConfig() {
    val secret = JwtConfig.getRawSecret()
    if (secret == DEFAULT_INSECURE_SECRET && !JwtConfig.isDevelopmentMode) {
        throw IllegalStateException(
            "Refusing to start: JWT secret is the built-in default. " +
                "Set JWT_SECRET to a strong value, or set JWT_DEVELOPMENT_MODE=true for local dev only.",
        )
    }
    if (JwtConfig.isDevelopmentMode) {
        assertDevelopmentModeIsSafeHere()
        logger.warn(
            "SECURITY: development mode is ON. LOGIN is bypassed (Bearer dev-bypass); authorization is NOT — " +
                "the capability gate still runs, and `$DEV_ACT_AS_HEADER: <groupCode>` chooses which principal " +
                "it decides for. Never use in production.",
        )
    }
}

/**
 * Refuses to start when development mode is on and the environment looks like a real deployment.
 *
 * The existing check runs the other way round: it lets dev mode EXCUSE an insecure JWT secret. So
 * the only thing standing between a production server and a wide-open login was somebody
 * remembering not to set the flag — and the flag is exactly the sort of thing that survives a copied
 * `.env` or a Docker image built from a dev machine.
 *
 * Each signal below is one a real deployment has and a laptop does not. Any single one is enough:
 * dev mode is cheap to turn off and an open server is not, so this errs toward refusing to boot.
 */
private fun assertDevelopmentModeIsSafeHere() {
    val reasons = mutableListOf<String>()

    if (System.getenv("DATABASE_MODE") == "external") {
        reasons += "DATABASE_MODE=external (a real deployment's database, not the bundled one)"
    }
    // Any of the common production-marker variables. Their VALUE does not matter — their presence
    // means someone configured this as a deployment.
    listOf("KUBERNETES_SERVICE_HOST", "ECS_CONTAINER_METADATA_URI", "ECS_CONTAINER_METADATA_URI_V4")
        .filter { System.getenv(it) != null }
        .forEach { reasons += "$it is set (running under an orchestrator)" }

    val env = System.getenv("POS_ENVIRONMENT")?.lowercase()
    if (env != null && env != "dev" && env != "development" && env != "local" && env != "test") {
        reasons += "POS_ENVIRONMENT=$env"
    }

    if (reasons.isNotEmpty()) {
        throw IllegalStateException(
            "Refusing to start: development mode is enabled, but this looks like a real deployment — " +
                reasons.joinToString("; ") +
                ". Development mode bypasses login entirely. Unset JWT_DEVELOPMENT_MODE.",
        )
    }
}
