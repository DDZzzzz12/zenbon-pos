package org.example.project.features.product.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.domain.service.ProductExtrasAssignmentService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.koin.ktor.ext.inject

/**
 * REST API routes for ProductExtrasAssignment operations.
 *
 * Migrated to throws-style in Stage B Batch 10. The service throws PosException;
 * the global StatusPages plugin converts to application/problem+json.
 */
fun Route.productExtrasAssignmentRoutes() {
    val service by inject<ProductExtrasAssignmentService>()

    authenticate("auth-jwt") {
        route("/api/product-extras-assignment") {
            /**
             * List every product-to-extra assignment.
             *
             * Description: Returns the full assignment table, which the POS caches to know which extras each product offers. Any authenticated employee may read it.
             *
             * Tag: product-extras-assignment
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                call.respond(HttpStatusCode.OK, service.getAllAssignments())
            }

            /**
             * Get assignments changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the assignments updated after the given timestamp.
             *
             * Tag: product-extras-assignment
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val lastSyncTime = call.parameters["lastSyncTime"]
                if (lastSyncTime.isNullOrBlank()) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Last sync time is required")
                }
                call.respond(HttpStatusCode.OK, service.getAssignmentsUpdatedAfter(lastSyncTime))
            }

            /**
             * List the extras assigned to one product.
             *
             * Description: What the POS calls when a cashier opens the extras panel for an order line. A product with no assignments yields an empty list, not a 404.
             *
             * Tag: product-extras-assignment
             * Path: productId Product identifier.
             * Response: 400 productId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/product/{productId}") {
                val productId =
                    call.parameters["productId"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid product ID")
                call.respond(HttpStatusCode.OK, service.getAssignmentsForProduct(productId))
            }

            /**
             * Assign extras to a product.
             *
             * Description: Requires MANAGER role. Replaces nothing — the listed extras are added to the product, and re-assigning one that is already attached is a no-op rather than an error.
             *
             * Tag: product-extras-assignment
             * Response: 400 The body failed validation, or a product or extra id is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post {
                val employeeId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductExtrasAssignmentDto.AssignRequestDto>()
                val dto = service.assignExtrasToProduct(employeeId, request.productId, request.extrasIds)
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Assign extras to every product in a category.
             *
             * Description: Requires MANAGER role. Fans the listed extras out over all products currently in the category — products added to the category later are not covered retroactively. The response reports how many assignments were created.
             *
             * Tag: product-extras-assignment
             * Response: 400 The body failed validation, or the category or an extra id is unknown.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post("/bulk-category") {
                val employeeId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductExtrasAssignmentDto.BulkCategoryAssignRequestDto>()
                val dto = service.bulkAssignExtrasToCategory(employeeId, request.categoryId, request.extrasIds)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Remove one extra from one product.
             *
             * Description: Requires MANAGER role. Detaches the extra from the product; neither the product nor the extra itself is deleted. Order lines already booked with that extra are unaffected.
             *
             * Tag: product-extras-assignment
             * Path: productId Product identifier.
             * Path: extrasId Product extra identifier.
             * Response: 204 Removed. No body.
             * Response: 400 productId or extrasId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 That extra is not assigned to that product (or either id is unknown).
             */
            delete("/{productId}/{extrasId}") {
                val employeeId = call.getEmployeeIdFromJwt()
                val productId =
                    call.parameters["productId"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid product ID")
                val extrasId =
                    call.parameters["extrasId"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid extras ID")
                service.removeExtrasFromProduct(employeeId, productId, extrasId)
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Remove one extra from every product in a category.
             *
             * Description: Requires MANAGER role. The inverse of the bulk assign — it detaches the extra from all products currently in the category.
             *
             * Tag: product-extras-assignment
             * Path: categoryId Product category identifier.
             * Path: extrasId Product extra identifier.
             * Response: 204 Removed. No body.
             * Response: 400 categoryId or extrasId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            delete("/bulk-category/{categoryId}/{extrasId}") {
                val employeeId = call.getEmployeeIdFromJwt()
                val categoryId =
                    call.parameters["categoryId"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid category ID")
                val extrasId =
                    call.parameters["extrasId"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid extras ID")
                service.bulkRemoveExtrasFromCategory(employeeId, categoryId, extrasId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
