package org.example.project.features.audit.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.service.ChangeFeedService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.changeFeedRoutes() {
    val service by inject<ChangeFeedService>()
    authenticate("auth-jwt") {
        route("/api/changes") {
            /**
             * Get the change feed.
             *
             * Description: The authoritative sync feed — every entity change since a sequence number, so an offline-capable client can catch up without polling each resource. `since` defaults to 0 (from the beginning) and must not be negative; `limit` defaults to 500 and must be at least 1. Replaces the retired per-resource timestamp sync routes.
             *
             * Tag: changes
             * Response: 400 since is negative, or limit is below 1.
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                if (since < 0) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "since must be >= 0",
                    )
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                if (limit < 1) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "limit must be >= 1",
                    )
                }
                call.respond(HttpStatusCode.OK, service.getChanges(since, limit))
            }
        }
    }
}
