package org.example.project.infrastructure.plugins

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.header
import io.ktor.server.response.*
import org.example.project.features.terminal.data.service.DeviceTokenGenerator
import org.example.project.features.terminal.data.service.TerminalCapability
import org.example.project.utils.formatter.currentTimeStamp

/**
 * Configuration for [DeviceTokenAuthenticationProvider]. A public subclass is required because
 * [AuthenticationProvider.Config]'s constructor is `protected`.
 */
class DeviceTokenAuthConfig(
    name: String?,
) : AuthenticationProvider.Config(name) {
    /** Resolves a device-token HASH to its terminal capability, or null if unknown. */
    lateinit var resolveCapability: suspend (tokenHash: String) -> TerminalCapability?
}

private val deviceTokenChallengeKey: Any = "DeviceTokenAuth"

/**
 * A Ktor [AuthenticationProvider] for the login-less order station's credential.
 *
 * The device token lives in the `X-Device-Token` HEADER, not `Authorization` — Ktor's built-in
 * `bearer { }` provider only ever reads `Authorization`, so a custom provider is used here
 * instead, reading the header directly. See A4 Task 5.
 */
class DeviceTokenAuthenticationProvider(
    config: DeviceTokenAuthConfig,
) : AuthenticationProvider(config) {
    private val resolveCapability = config.resolveCapability

    private fun reject(
        context: AuthenticationContext,
        cause: AuthenticationFailedCause,
        message: String,
    ) {
        context.challenge(deviceTokenChallengeKey, cause) { challenge, call ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to message))
            challenge.complete()
        }
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val token =
            context.call.request
                .header("X-Device-Token")
                ?.takeIf { it.isNotBlank() }
        if (token == null) {
            reject(context, AuthenticationFailedCause.NoCredentials, "Missing X-Device-Token")
            return
        }

        val cap = resolveCapability(DeviceTokenGenerator.hash(token))
        if (cap == null) {
            reject(context, AuthenticationFailedCause.InvalidCredentials, "Invalid device token")
            return
        }

        val expiresAt = cap.tokenExpiresAt
        if (expiresAt != null && expiresAt < currentTimeStamp()) {
            reject(context, AuthenticationFailedCause.InvalidCredentials, "Device token expired")
            return
        }

        // Defense-in-depth: the terminal-capability ceiling in RouteAuthorizationPlugin already
        // refuses a deactivated terminal on the transactional routes this provider is used for,
        // but the principal itself must never be issued for a deactivated terminal either — this
        // keeps the guard correct even if a future route wires up "device-principal" auth without
        // being registered in TransactionalRoutes.
        if (!cap.isActive) {
            reject(context, AuthenticationFailedCause.InvalidCredentials, "Terminal is deactivated")
            return
        }

        context.principal(name, DeviceTokenPrincipal(cap))
    }
}

/** Installs the [DeviceTokenAuthenticationProvider] under [name]. */
fun AuthenticationConfig.deviceToken(
    name: String? = null,
    configure: DeviceTokenAuthConfig.() -> Unit,
) {
    val config = DeviceTokenAuthConfig(name).apply(configure)
    register(DeviceTokenAuthenticationProvider(config))
}
