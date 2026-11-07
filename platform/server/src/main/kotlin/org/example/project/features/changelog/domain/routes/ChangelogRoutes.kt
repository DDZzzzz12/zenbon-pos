/**
 * REST API routes for changelog release notes.
 *
 * Throws-style: the service throws PosException; the global StatusPages plugin
 * converts to application/problem+json. Read-only, paginated, accessible to every
 * authenticated user (release notes are not business-sensitive).
 */
package org.example.project.features.changelog.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.example.project.features.changelog.domain.service.ChangelogService
import org.koin.ktor.ext.inject

fun Route.changelogRoutes() {
    val service by inject<ChangelogService>()

    authenticate("auth-jwt") {
        route("/api/v1/changelog") {
            /**
             * List release notes.
             *
             * Description: The user-facing what's-new entries, newest first. Readable by every authenticated employee — release notes are not business-sensitive. Pages with `page` (from 0) and `size` (default 20).
             *
             * Tag: changelog
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
                call.respond(HttpStatusCode.OK, service.list(page, size))
            }
        }
    }
}
