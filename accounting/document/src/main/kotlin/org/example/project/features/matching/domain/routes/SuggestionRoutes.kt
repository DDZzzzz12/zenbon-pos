/**
 * Match Suggestion API Route — what one payment probably settles.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * Endpoints:
 * - GET /api/allocations/suggestions?transactionId= - The ranked suggestions for one transaction
 */
package org.example.project.features.matching.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.example.project.core.error.PosErrorCode
import org.example.project.features.matching.domain.service.MatchSuggestionService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.suggestionRoutes() {
    val suggestionService by inject<MatchSuggestionService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/allocations") {
            /**
             * What this payment probably settles.
             *
             * Description: Requires MANAGER role. Ranks the open invoices and contract instalments against one bank transaction and returns what might settle it, strongest first. An empty list is a normal answer and comes back as 200 - most payments match nothing provable, and a reversal or an entry on a statement not yet assigned to one of our accounts answers the same way. Nothing is applied automatically and ties are never collapsed into a default: a suggestion is offered, and a human accepts it.
             *
             * Tag: allocations
             * Query: transactionId The bank transaction to explain. Required.
             * Response: 200 The suggestions, strongest first, possibly none.
             * Response: 400 transactionId is absent or not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No bank transaction with that id.
             */
            get("/suggestions") {
                val raw =
                    call.request.queryParameters["transactionId"]
                        ?: throw invalidRequest("transactionId is required")
                val transactionId = raw.toLongOrNull() ?: throw invalidRequest("transactionId must be a number")
                call.respond(HttpStatusCode.OK, suggestionService.suggestionsFor(transactionId))
            }
        }
    }
}

private fun invalidRequest(message: String): PosException = PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, message)
