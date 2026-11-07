/**
 * REST API routes for ProductCategory operations.
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
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.domain.service.ProductCategoryService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.infrastructure.plugins.getTokenClaims
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ProductCategoryRoutes")

fun Route.productCategoryRoutes() {
    val productCategoryService by inject<ProductCategoryService>()

    authenticate("auth-jwt") {
        route("/api/product-categories") {
            /**
             * List product categories.
             *
             * Description: Any authenticated employee may read these — the POS needs them to render its category tabs, in their configured sort order. Pass `includeInactive=true` to include deactivated categories (default false).
             *
             * Tag: product-categories
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false
                call.respond(HttpStatusCode.OK, productCategoryService.getAllCategories(includeInactive))
            }

            /**
             * Get a product category by ID.
             *
             * Tag: product-categories
             * Path: id Product category identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No product category with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid category ID")
                call.respond(HttpStatusCode.OK, productCategoryService.getCategoryById(id))
            }

            /**
             * Create a product category.
             *
             * Description: Requires MANAGER role. The acting employee is taken from the JWT and written to the entry log, and connected clients are notified so their category tabs refresh.
             *
             * Tag: product-categories
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductCategoryDto.CreateProductCategoryRequestDto>()
                val dto = productCategoryService.createCategory(userId, request)
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Update a product category.
             *
             * Description: Requires MANAGER role. Only the fields present in the body are changed. Connected clients are notified of the change.
             *
             * Tag: product-categories
             * Path: id Product category identifier.
             * Response: 400 id is not a number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No product category with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid category ID")
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductCategoryDto.UpdateProductCategoryRequestDto>()
                val dto = productCategoryService.updateCategory(userId, id, request)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Get product categories changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the categories updated after the given timestamp.
             *
             * Tag: product-categories
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val lastSyncTime = call.parameters["lastSyncTime"]
                if (lastSyncTime.isNullOrBlank()) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Last sync time is required")
                }
                call.respond(HttpStatusCode.OK, productCategoryService.getCategoriesUpdatedAfter(lastSyncTime))
            }

            /**
             * Delete a product category.
             *
             * Description: Requires MANAGER role. The delete is a soft delete (the category is deactivated, so historical articles keep resolving), and it is refused while the category still holds active products — move or deactivate them first. Connected clients are notified.
             *
             * Tag: product-categories
             * Path: id Product category identifier.
             * Response: 204 Deleted. No body.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No product category with that id.
             * Response: 409 The category still contains active products (`DELETE_HAS_REFERENCES`); the detail names how many.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid category ID")
                val userId = call.getEmployeeIdFromJwt()
                productCategoryService.deleteCategory(userId, id)
                call.respond(HttpStatusCode.NoContent)
            }

            /**
             * Reorder product categories.
             *
             * Description: Requires MANAGER role. Takes the whole list of category-to- sort-position pairs from the drag-and-drop editor and applies them one by one. Partial failure is tolerated — the response always reports success and failure counts, but the status is 400 if any single update failed.
             *
             * Tag: product-categories
             * Response: 400 At least one category position could not be updated.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER, or the token carries no usable claims.
             */
            put("/positions") {
                val request = call.receive<List<ProductCategoryDto.UpdateProductCategoryPositionRequestDto>>()
                val claim =
                    call.getTokenClaims()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.Forbidden, "Invalid authentication token")

                var successCount = 0
                var failedCount = 0
                for (update in request) {
                    try {
                        productCategoryService.updateCategoryPosition(
                            categoryId = update.categoryId.toLong(),
                            newSortPosition = update.sortOrder,
                            employeeId = claim.employeeId,
                        )
                        successCount++
                    } catch (e: PosException) {
                        logger.warn("updateCategoryPosition failed for categoryId=${update.categoryId}: ${e.message}")
                        failedCount++
                    } catch (e: Exception) {
                        logger.error("updateCategoryPosition error for categoryId=${update.categoryId}", e)
                        failedCount++
                    }
                }

                if (failedCount == 0) {
                    call.respond(
                        HttpStatusCode.OK,
                        ApiResponseDto.bulkOperation(successCount = successCount, failedCount = 0, totalCount = request.size),
                    )
                    logger.info("Updated ${request.size} category positions")
                } else {
                    logger.warn("Failed to update $failedCount category positions")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponseDto.bulkOperation(successCount = successCount, failedCount = failedCount, totalCount = request.size),
                    )
                }
            }

            /**
             * Bulk-import product categories from CSV.
             *
             * Description: Requires MANAGER role. Send the file as a multipart upload. The import is row-tolerant — the response reports per-row success and error counts, and one bulk entry-log record is written for the run.
             *
             * Tag: product-categories
             * Path: overwrite Set true to update categories that already exist, false to skip them.
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

                logger.info("Processing CSV import for categories: $fileName (${bytes.size} bytes) by user $userId, overwrite=$overwrite")

                val result = productCategoryService.importCategoriesCsv(bytes, overwrite)
                dbQuery {
                    EntryLogHelper.logOperation(
                        type = ChangeEntityType.CATEGORY,
                        operation = LogOperation.CREATE,
                        entityId = "bulk-import",
                        entityName = "CSV Import: ${result.successCount} categories imported",
                        message = "Bulk imported ${result.successCount} categories from CSV (${result.errorCount} failed)",
                        userId = userId,
                    )
                }
                call.respond(HttpStatusCode.OK, result)
            }
        }
    }
}
