/**
 * API routes of the config-defined maintainer account.
 *
 * Off unless `MAINTAINER_USERNAME` and `MAINTAINER_PASSWORD_HASH` are set; while off every route here
 * answers 404. The maintainer is not an employee: it has no permission group, and its token opens
 * nothing outside `/api/maintenance` (see `MaintainerContainmentTest`).
 *
 * @since 1.2.0
 */
package org.example.project.features.maintenance.domain.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.core.error.PosErrorCode
import org.example.project.features.maintenance.MaintenancePolicy
import org.example.project.features.maintenance.data.logs.LogQuery
import org.example.project.features.maintenance.data.service.LogSearchService
import org.example.project.features.maintenance.data.service.MaintenanceAuthService
import org.example.project.features.maintenance.data.service.MaintenanceAuthService.Outcome
import org.example.project.features.maintenance.domain.dto.MaintenanceDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.requireMaintainer
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Configure the maintenance routes.
 *
 * Endpoints:
 * - POST /api/maintenance/login — exchange the maintainer's credentials for a short-lived token
 * - GET /api/maintenance/logs — search the server logs
 */
fun Route.maintenanceRoutes() {
    val authService by inject<MaintenanceAuthService>()
    val searchService by inject<LogSearchService>()
    val logger = LoggerFactory.getLogger("MaintenanceRoutes")

    route("/api/maintenance") {
        /**
         * Log in as the maintainer.
         *
         * Description: Exchanges the maintainer's username and password (configured on the server, not an employee account) for a short-lived access token that opens only the maintenance API. Answers 404 while the maintainer account is not configured. After five consecutive failures further attempts are refused with 429 and a Retry-After header, starting at 30 seconds and doubling up to 15 minutes.
         *
         * Tag: maintenance
         * Response: 401 The username or password is wrong.
         * Response: 404 The maintainer account is not configured on this server.
         * Response: 429 Too many consecutive failures; wait for Retry-After seconds.
         */
        post("/login") {
            // The route is `Public` in the rule table (a login must be reachable before any principal
            // exists), so the "feature is off" 404 has to be answered here.
            if (!MaintenancePolicy.enabled) return@post call.respond(HttpStatusCode.NotFound)

            val request = call.receive<MaintenanceDto.LoginRequestDto>()
            when (val outcome = authService.login(request.username, request.password)) {
                is Outcome.Granted -> {
                    // The body is a bearer credential: no proxy or browser cache may keep it.
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respond(
                        HttpStatusCode.OK,
                        MaintenanceDto.LoginResponseDto(outcome.accessToken, outcome.expiresInSeconds),
                    )
                }
                is Outcome.Denied ->
                    throw PosException(
                        PosErrorCode.AUTH_INVALID_CREDENTIALS,
                        HttpStatusCode.Unauthorized,
                        "Invalid maintainer credentials",
                    )
                is Outcome.Throttled -> {
                    call.response.header(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                    throw PosException(
                        PosErrorCode.MAINTENANCE_LOGIN_THROTTLED,
                        HttpStatusCode.TooManyRequests,
                        "Too many failed attempts",
                    )
                }
            }
        }

        /**
         * Search the server logs.
         *
         * Description: Searches rejected.log, error.log, client-error.log and application.log (current, rotated and compressed files) and returns matching entries newest first. Each entry has structured fields plus message and exception text scrubbed of numbers, quoted strings, e-mail addresses, IBANs, UUIDs and values that follow words like "user" or "employee"; employee ids, device ids, usernames and employee numbers are never returned, and the message and exception text of the authentication and employee loggers are withheld whole. The free-text filter q is matched against the scrubbed text only. Without a traceId the search covers the last 24 hours unless from is given; the response's from field carries the start actually used. The response says when a scan was cut short.
         *
         * Tag: maintenance
         * Query: traceId Exact trace id (the X-Request-ID the error dialog showed). Searches the whole retention when given.
         * Query: from Earliest time, ISO-8601 instant. Default: 24 hours ago, unless traceId is given.
         * Query: to Latest time, ISO-8601 instant.
         * Query: level Minimum level: TRACE, DEBUG, INFO, WARN or ERROR.
         * Query: source rejected, error, client-error or application. Repeatable or comma-separated; default all.
         * Query: code Exact error code, e.g. AUTH_FORBIDDEN.
         * Query: status Exact HTTP status.
         * Query: q Free text, matched case-insensitively against the scrubbed text.
         * Query: limit Entries to return, 1 to 200, default 100.
         * Response: 400 A filter is malformed.
         * Response: 401 The maintainer token is missing, invalid or expired.
         * Response: 404 The maintainer account is not configured on this server.
         * Response: 503 The server's log directory cannot be read.
         */
        get("/logs") {
            call.requireMaintainer()
            val query = LogQuery.fromParameters(call.request.queryParameters, Instant.now())
            val response = withContext(Dispatchers.IO) { searchService.search(query) }
            logger.info(
                "Maintenance log search: filters={} matched={} truncated={}",
                query.activeFilterNames(),
                response.matched,
                response.truncated,
            )
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
