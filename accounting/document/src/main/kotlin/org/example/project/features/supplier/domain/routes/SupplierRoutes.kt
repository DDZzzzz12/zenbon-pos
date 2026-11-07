/**
 * Supplier API Routes — creditor master data.
 *
 * CRITICAL AUTHENTICATION PATTERN:
 * - Single authenticate("auth-jwt") wrapper for entire route group
 * - Prevents "Default authentication configuration was not found" runtime error
 * - Never nest multiple authenticate blocks
 *
 * Endpoints:
 * - GET  /api/suppliers        - List suppliers; search, sort and the provisional filter are all server-side
 * - GET  /api/suppliers/{id}   - One supplier with its known IBANs
 * - POST /api/suppliers        - Create a supplier
 * - PUT  /api/suppliers/{id}   - Replace the editable fields; confirms a provisional supplier
 */
package org.example.project.features.supplier.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.example.project.features.supplier.domain.service.SupplierService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject

fun Route.supplierRoutes() {
    val supplierService by inject<SupplierService>()

    // CRITICAL: Single authenticate wrapper for all endpoints
    authenticate("auth-jwt") {
        route("/api/suppliers") {
            /**
             * List creditors.
             *
             * Description: Active suppliers, ordered by the sort parameter — name, vatId, creditorNo or status, name by default, reversed with ascending=false. Set provisionalOnly to narrow the list to suppliers created without a VAT ID, which a human still has to confirm. The search parameter matches name, VAT ID or creditor number case-insensitively; a blank search is ignored rather than returning nothing, and an unknown sort falls back to name rather than failing the read.
             *
             * Tag: suppliers
             * Response: 200 The supplier list with a total count.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                val provisionalOnly =
                    call.request.queryParameters["provisionalOnly"]?.toBooleanStrictOrNull() ?: false
                val search = call.request.queryParameters["search"]
                val sort = SupplierSort.parse(call.request.queryParameters["sort"])
                val ascending =
                    call.request.queryParameters["ascending"]?.toBooleanStrictOrNull() ?: true
                val limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                call.respond(
                    HttpStatusCode.OK,
                    supplierService.list(provisionalOnly, search, sort, ascending, limit, offset),
                )
            }

            /**
             * Fetch one creditor.
             *
             * Description: Returns the supplier together with every IBAN it has been seen to use.
             *
             * Tag: suppliers
             * Path: id Supplier identifier.
             * Response: 200 The supplier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No supplier with that id.
             */
            get("/{id}") {
                call.respond(HttpStatusCode.OK, supplierService.get(call.longParam("id")))
            }

            /**
             * Create a creditor.
             *
             * Description: Requires MANAGER role. Creates a confirmed supplier — resolution creates provisional ones on its own when a scan carries no VAT ID.
             *
             * Tag: suppliers
             * Response: 201 The created supplier.
             * Response: 400 The body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 SUPPLIER_VAT_ID_TAKEN — another creditor already carries that VAT ID.
             */
            post {
                val dto = supplierService.create(call.receive<SupplierDto.CreateRequestDto>())
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Replace a creditor's editable fields.
             *
             * Description: Requires MANAGER role. A full replace, not a patch — an omitted optional field is written as null. A successful update also clears the provisional flag, because an operator who checked the details has confirmed the supplier.
             *
             * Tag: suppliers
             * Path: id Supplier identifier.
             * Response: 200 The updated supplier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No supplier with that id.
             * Response: 409 SUPPLIER_VAT_ID_TAKEN — another creditor already carries that VAT ID.
             */
            put("/{id}") {
                val dto =
                    supplierService.update(
                        call.longParam("id"),
                        call.receive<SupplierDto.UpdateRequestDto>(),
                    )
                call.respond(HttpStatusCode.OK, dto)
            }
        }
    }
}

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull()
        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid $name")
