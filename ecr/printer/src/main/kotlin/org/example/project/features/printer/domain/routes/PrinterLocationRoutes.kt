/**
 * Ktor routes for printer location management API endpoints.
 *
 * Provides REST API endpoints for printer location operations including:
 * - Create printer location
 * - Get all printer locations (with active/inactive filter)
 * - Update printer location
 * - Deactivate (soft delete) printer location
 * - Activate printer location
 *
 * All routes are protected with JWT authentication. Routes throw [PosException]
 * on parameter-validation failures; service errors propagate via StatusPages.
 */
package org.example.project.features.printer.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.getEmployeeIdFromJwt
import org.example.project.features.printer.domain.service.PrinterLocationService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

/**
 * Configures all printer location-related routes.
 *
 * IMPORTANT: All routes must be wrapped in a SINGLE authenticate block to prevent
 * "Default authentication configuration was not found" runtime errors.
 */
fun Route.printerLocationRoutes() {
    val printerLocationService by inject<PrinterLocationService>()

    // CRITICAL: Single authenticate wrapper for all routes
    authenticate("auth-jwt") {
        route("/api/printer-location") {
            /**
             * Create a printer location.
             *
             * Description: A printer location binds a printer setup to a purpose — receipt, kitchen, bar — which is what order routing targets rather than a raw printer address. The creating employee is taken from the JWT.
             *
             * Tag: printer-locations
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<PrinterDto.CreatePrinterLocationRequestDto>()
                val response = printerLocationService.createPrinterLocation(userId ?: "", request)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * List printer locations.
             *
             * Description: Pass `includeInactive=true` to include deactivated locations (default false).
             *
             * Tag: printer-locations
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                val response = printerLocationService.getAllPrinterLocations(includeInactive)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Update a printer location.
             *
             * Description: Only the fields present in the body are changed. Repointing a location at a different printer takes effect on the next print.
             *
             * Tag: printer-locations
             * Path: id Printer location identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No printer location with that id.
             */
            put("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid printer location ID",
                        )
                val request = call.receive<PrinterDto.UpdatePrinterLocationRequestDto>()
                val response = printerLocationService.updatePrinterLocation(userId ?: "", id, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Deactivate a printer location.
             *
             * Description: A soft delete — the location is flagged inactive so print-job history keeps resolving it, and order routing stops targeting it. Use the activate route to bring it back.
             *
             * Tag: printer-locations
             * Path: id Printer location identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No printer location with that id.
             */
            delete("/{id}") {
                val userId = call.getEmployeeIdFromJwt()
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid printer location ID",
                        )
                printerLocationService.deactivatePrinterLocation(userId ?: "", id)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Printer location deactivated successfully"))
            }

            /**
             * Activate a printer location.
             *
             * Description: Restores a deactivated location so order routing targets it again.
             *
             * Tag: printer-locations
             * Path: id Printer location identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No printer location with that id.
             */
            patch("/{id}/activate") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Invalid printer location ID",
                        )
                printerLocationService.activatePrinterLocation(id)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Printer location activated successfully"))
            }
        }
    }
}
