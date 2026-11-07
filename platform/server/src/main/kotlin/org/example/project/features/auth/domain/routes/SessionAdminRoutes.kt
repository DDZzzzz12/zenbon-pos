package org.example.project.features.auth.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.auth.data.SessionPolicyDto
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

private val TIME_REGEX = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

/**
 * Session administration routes.
 *
 * Lets admins (a) configure the daily session-reset policy and
 * (b) trigger a shop-wide "log everyone out now" panic cutoff.
 *
 * Authorization is enforced both by the central [org.example.project.infrastructure.security.RoutePermissions]
 * registry (RouteAuthorizationPlugin), which gates both mutating routes on SESSION_REVOKE.
 */
fun Routing.sessionAdminRoutes() {
    val configManager by inject<IShopConfigurationManager>()

    authenticate("auth-jwt") {
        route("/api/auth/sessions") {
            /**
             * Get the daily session-reset policy.
             *
             * Description: Returns whether the shop-wide nightly logout is enabled and the local time it fires. Readable by any authenticated employee, since clients show the operator when their session will end.
             *
             * Tag: auth-sessions
             * Response: 401 Missing or invalid bearer token.
             */
            get("/policy") {
                val p = configManager.getTokenSessionPolicy()
                call.respond(HttpStatusCode.OK, SessionPolicyDto.Policy(p.dailyResetEnabled, p.dailyResetTime))
            }
            /**
             * Update the daily session-reset policy.
             *
             * Description: Requires SESSION_REVOKE. `dailyResetTime` must be `HH:mm` in the 24-hour range 00:00–23:59; the new policy takes effect for the next scheduled cutoff.
             *
             * Tag: auth-sessions
             * Response: 400 dailyResetTime is not a valid 24-hour time (hour and minute).
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks SESSION_REVOKE.
             */
            put("/policy") {
                val req = call.receive<SessionPolicyDto.UpdatePolicyRequest>()
                if (!TIME_REGEX.matches(req.dailyResetTime)) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.BadRequest,
                        "dailyResetTime must be HH:mm (00:00–23:59)",
                    )
                }
                configManager.setTokenDailyReset(req.dailyResetEnabled, req.dailyResetTime)
                call.respond(HttpStatusCode.OK, SessionPolicyDto.Policy(req.dailyResetEnabled, req.dailyResetTime))
            }
            /**
             * Log every employee out immediately.
             *
             * Description: Requires SESSION_REVOKE. Panic cutoff — moves the shop-wide session boundary to now, so every issued token is rejected from the next request onwards and all staff must log in again, including the caller.
             *
             * Tag: auth-sessions
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks SESSION_REVOKE.
             */
            post("/revoke-all") {
                configManager.triggerManualSessionCutoff()
                call.respond(HttpStatusCode.OK, "All sessions invalidated")
            }
        }
    }
}
