/**
 * REST API routes for ProductExtras operations.
 *
 * Migrated to throws-style in Stage B Batch 10. The service throws PosException;
 * the global StatusPages plugin converts to application/problem+json.
 */
package org.example.project.features.product.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.domain.service.ProductExtrasService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ProductExtrasRoutes")

fun Route.productExtrasRoutes() {
    val productExtrasService by inject<ProductExtrasService>()

    authenticate("auth-jwt") {
        route("/api/product-extras") {
            /**
             * List product extras.
             *
             * Description: Extras are the priced add-ons a cashier can attach to an order line. Any authenticated employee may read them. Pass `includeInactive=true` to include deactivated extras (default false).
             *
             * Tag: product-extras
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, productExtrasService.getAllExtras(includeInactive))
            }

            /**
             * Get a product extra by ID.
             *
             * Tag: product-extras
             * Path: id Product extra identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No product extra with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid extra ID")
                call.respond(HttpStatusCode.OK, productExtrasService.getExtraById(id))
            }

            /**
             * Get product extras changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the extras updated after the given timestamp.
             *
             * Tag: product-extras
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val lastSyncTime = call.parameters["lastSyncTime"]
                if (lastSyncTime.isNullOrBlank()) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Last sync time is required")
                }
                call.respond(HttpStatusCode.OK, productExtrasService.getExtrasUpdatedAfter(lastSyncTime))
            }

            /**
             * Create a product extra.
             *
             * Description: Requires MANAGER role. The price is stored as an exact decimal. The acting employee is taken from the JWT and written to the entry log.
             *
             * Tag: product-extras
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductExtrasDto.CreateProductExtrasRequestDto>()
                val dto = productExtrasService.createExtra(userId, request)
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Update a product extra.
             *
             * Description: Requires MANAGER role. Only the fields present in the body are changed. A price change applies to future order lines only — lines already booked keep the price snapshotted at the time of sale.
             *
             * Tag: product-extras
             * Path: id Product extra identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No product extra with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid extra ID")
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductExtrasDto.UpdateProductExtrasRequestDto>()
                val dto = productExtrasService.updateExtra(userId, id, request)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Delete a product extra.
             *
             * Description: Requires MANAGER role. Extras still referenced by order lines are deactivated rather than removed, so historical bills stay resolvable. Assignments to products are removed with it.
             *
             * Tag: product-extras
             * Path: id Product extra identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No product extra with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid extra ID")
                val userId = call.getEmployeeIdFromJwt()
                productExtrasService.deleteExtra(userId, id)
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Bulk-import product extras from CSV.
             *
             * Description: Requires MANAGER role. Send the file as a multipart upload. The import is row-tolerant — the response reports per-row success and error counts, and one bulk entry-log record is written for the run.
             *
             * Tag: product-extras
             * Path: overwrite Set true to update extras that already exist, false to skip them.
             * Response: 400 The request carried no CSV file part.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post("/import-csv/{overwrite}") {
                val userId = call.getEmployeeIdFromJwt()
                val overwrite = call.parameters["overwrite"]?.toBooleanStrictOrNull() ?: false

                var csvBytes: ByteArray? = null
                var fileName: String? = null

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            fileName = part.originalFileName
                            csvBytes = part.provider().readRemaining().readByteArray()
                        }
                        else -> part.dispose()
                    }
                }

                val bytes =
                    csvBytes
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "No CSV file provided")

                logger.info(
                    "Processing CSV import for product extras: $fileName (${bytes.size} bytes) by user $userId, overwrite=$overwrite",
                )

                val result = productExtrasService.importExtrasCsv(bytes, overwrite)
                dbQuery {
                    EntryLogHelper.logOperation(
                        type = ChangeEntityType.PRODUCT_EXTRA,
                        operation = LogOperation.CREATE,
                        entityId = "bulk-import",
                        entityName = "CSV Import: ${result.successCount} extras imported",
                        message = "Bulk imported ${result.successCount} product extras from CSV (${result.errorCount} failed)",
                        userId = userId,
                    )
                }
                call.respond(HttpStatusCode.OK, result)
            }
        }
    }
}
