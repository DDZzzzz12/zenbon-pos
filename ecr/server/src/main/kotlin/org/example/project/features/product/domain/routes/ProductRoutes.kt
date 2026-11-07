/**
 * REST API routes for Product operations.
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
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.product.domain.service.FavouriteProductService
import org.example.project.features.product.domain.service.ProductService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.utils.ImageUtils
import org.example.project.utils.dbQuery
import org.example.project.utils.log.EntryLogHelper
import org.koin.ktor.ext.inject
import java.io.File
import java.util.UUID

private val logger = org.slf4j.LoggerFactory.getLogger("ProductRoutes")

fun Route.productRoutes() {
    val productService by inject<ProductService>()
    val favouriteProductService by inject<FavouriteProductService>()
    val configManager by inject<IShopConfigurationManager>()

    authenticate("auth-jwt") {
        route("/api/products") {
            /**
             * List products.
             *
             * Description: Any authenticated employee may read the catalogue. Optional query parameters `limit` and `offset` page the result (offset defaults to 0, no limit by default), and `active=true|false` restricts to active or inactive products only. Omitting `active` returns both.
             *
             * Tag: products
             * Response: 401 Missing or invalid bearer token.
             */
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val active = call.request.queryParameters["active"]?.toBooleanStrictOrNull()
                call.respond(HttpStatusCode.OK, productService.getAllProducts(limit, offset, active))
            }

            /**
             * Search products by name.
             *
             * Description: The search term comes from the required `q` query parameter. `limit` (default 20) and `offset` (default 0) page the result, and `includeInactive=true` widens it to deactivated products.
             *
             * Tag: products
             * Response: 400 The q query parameter is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/search") {
                val query =
                    call.request.queryParameters["q"]
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "Search query parameter 'q' is required",
                        )
                if (query.isBlank()) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Search query parameter 'q' is required")
                }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val includeInactive = call.request.queryParameters["includeInactive"]?.toBoolean() ?: false

                call.respond(HttpStatusCode.OK, productService.searchProductsByName(query, limit, offset, includeInactive))
            }

            /**
             * List the products in a category.
             *
             * Description: Drives the POS category tabs. `limit` and `offset` query parameters page the result. An unknown category yields an empty list rather than a 404.
             *
             * Tag: products
             * Path: categoryId Product category identifier.
             * Response: 400 categoryId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/by-category/{categoryId}") {
                val categoryId =
                    call.parameters["categoryId"]?.toIntOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid category ID")

                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                call.respond(HttpStatusCode.OK, productService.getProductsByCategory(categoryId, limit, offset))
            }

            /**
             * Get a product by its product number.
             *
             * Description: Lookup by the operator-facing article number, as typed on the POS keypad or read from a barcode, rather than by database id.
             *
             * Tag: products
             * Path: productNumber Operator-facing article number of the product.
             * Response: 400 productNumber is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No product carries that product number.
             */
            get("/by-number/{productNumber}") {
                val productNumber = call.parameters["productNumber"]
                if (productNumber.isNullOrBlank()) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Product number is required")
                }
                call.respond(HttpStatusCode.OK, productService.getProductByNumber(productNumber))
            }

            /**
             * Get a product by ID.
             *
             * Tag: products
             * Path: id Product identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No product with that id.
             */
            get("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid product ID")
                call.respond(HttpStatusCode.OK, productService.getProductById(id))
            }

            /**
             * Get products changed since a timestamp.
             *
             * Description: Incremental sync feed for offline-capable clients — returns only the products updated after the given timestamp.
             *
             * Tag: products
             * Path: lastSyncTime ISO-8601 timestamp of the client's last successful sync.
             * Response: 400 lastSyncTime is missing or blank.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/sync/{lastSyncTime}") {
                val lastSyncTime = call.parameters["lastSyncTime"]
                if (lastSyncTime.isNullOrBlank()) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Last sync time is required")
                }
                call.respond(HttpStatusCode.OK, productService.getProductsUpdatedAfter(lastSyncTime))
            }

            /**
             * Create a product.
             *
             * Description: Requires MANAGER role. The product number is trimmed and upper-cased before storage. A `pfandTypeId` must reference an active Pfand type. The acting employee is taken from the JWT and written to the entry log, and connected clients are notified of the new product.
             *
             * Tag: products
             * Response: 400 The request body failed validation, or pfandTypeId is unknown or inactive.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 409 A product with that product number already exists.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductDto.CreateProductRequestDto>()
                val dto = productService.createProduct(userId, request)
                call.respond(HttpStatusCode.Created, dto)
            }

            /**
             * Update a product.
             *
             * Description: Requires MANAGER role. Only the fields present in the body are changed. Replacing the image URL deletes the previously stored image file. `pfandTypeId` is not optional in effect: omitting it removes the product's Pfand-type link, so a client must send the current value back to keep it. An unchanged link is kept even if its Pfand type has since been deactivated; a new link must name an active type. A Pfand type's own deposit or return product must keep its link unchanged. Connected clients are notified of the change.
             *
             * Tag: products
             * Path: id Product identifier.
             * Response: 400 id is not a number, the body failed validation, pfandTypeId newly points at an unknown or inactive Pfand type, or the product is a Pfand type's deposit/return product and pfandTypeId differs from its current type.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No product with that id.
             */
            put("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid product ID")
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ProductDto.UpdateProductRequestDto>()
                val dto = productService.updateProduct(userId, id, request)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Delete a product.
             *
             * Description: Requires MANAGER role. Products already referenced by order lines are deactivated instead of removed, so historical bills stay resolvable; the response names which happened. A genuine delete also removes the stored image file.
             *
             * Tag: products
             * Path: id Product identifier.
             * Response: 400 id is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No product with that id.
             */
            delete("/{id}") {
                val id =
                    call.parameters["id"]?.toLongOrNull()
                        ?: throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid product ID")
                val userId = call.getEmployeeIdFromJwt()
                val name = productService.deleteProduct(userId, id)
                call.respond(HttpStatusCode.OK, name)
            }

            /**
             * Bulk-import products from CSV.
             *
             * Description: Requires MANAGER role. Send the file as a multipart upload. The import is row-tolerant — the response reports per-row success and error counts rather than failing the whole file, and one bulk entry-log record is written for the run.
             *
             * Tag: products
             * Path: overwrite Set true to update products whose product number already exists, false to skip them.
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

                logger.info("Processing CSV import for products: $fileName (${bytes.size} bytes) by user $userId")

                val result = productService.importProductsCsv(bytes, overwrite)
                dbQuery {
                    EntryLogHelper.logOperation(
                        type = ChangeEntityType.PRODUCT,
                        operation = LogOperation.CREATE,
                        entityId = "bulk-import",
                        entityName = "CSV Import: ${result.successCount} products imported",
                        message = "Bulk imported ${result.successCount} products from CSV (${result.errorCount} failed)",
                        userId = userId,
                    )
                }
                call.respond(HttpStatusCode.OK, result)
            }

            /**
             * Get the most-sold products.
             *
             * Description: Ranks products by sales over a trailing window and returns them with their analytics, for the POS favourites panel. The `periodDays` query parameter sets the window (default 30) and `limit` caps how many products come back (default 20).
             *
             * Tag: products
             * Response: 401 Missing or invalid bearer token.
             */
            get("/favourites") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val periodDays = call.request.queryParameters["periodDays"]?.toIntOrNull() ?: 30
                val data = favouriteProductService.getFavouritesWithAnalytics(periodDays)
                val filteredData = data.copy(products = data.products.take(limit))
                call.respond(HttpStatusCode.OK, filteredData)
            }

            /**
             * Upload a product image.
             *
             * Description: Requires MANAGER role. Send the image as a multipart upload. Accepts jpg, jpeg, png and webp up to 10 MB raw; the file is re-compressed to JPEG and stored under the configured document storage path. The response carries the public URL to put on a product.
             *
             * Tag: products
             * Response: 400 The request carried no image file part.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 413 The uploaded image exceeds the 10 MB limit.
             * Response: 415 The file extension is not jpg, jpeg, png or webp.
             * Response: 500 The image could not be compressed or written to disk.
             */
            post("/upload-image") {
                val userId = call.getEmployeeIdFromJwt()
                var imageBytes: ByteArray? = null
                var originalFileName: String? = null

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            originalFileName = part.originalFileName
                            imageBytes = part.provider().readRemaining().readByteArray()
                        }
                        else -> part.dispose()
                    }
                }

                val bytes = imageBytes
                val name = originalFileName
                if (bytes == null || name == null) {
                    throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "No image file provided")
                }

                val allowedExtensions = setOf("jpg", "jpeg", "png", "webp")
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in allowedExtensions) {
                    throw PosException(
                        PosErrorCode.IMAGE_TYPE_UNSUPPORTED,
                        HttpStatusCode.UnsupportedMediaType,
                        "Image type not supported: .$extension (allowed: $allowedExtensions)",
                    )
                }

                val maxSize = 10 * 1024 * 1024 // 10MB raw upload limit
                if (bytes.size > maxSize) {
                    throw PosException(
                        PosErrorCode.IMAGE_TOO_LARGE,
                        HttpStatusCode.PayloadTooLarge,
                        "Image file too large. Maximum size: 10MB",
                    )
                }

                val compressedBytes =
                    try {
                        ImageUtils.compressImage(bytes)
                    } catch (e: Exception) {
                        throw PosException(
                            PosErrorCode.INTERNAL_ERROR,
                            HttpStatusCode.InternalServerError,
                            "Failed to process image: ${e.message}",
                            cause = e,
                        )
                    }

                val storagePath = configManager.getDocumentStoragePath()
                val imageDir = File(storagePath, "product-images")
                imageDir.mkdirs()

                val savedFileName = "${UUID.randomUUID()}.jpg"
                val destFile = File(imageDir, savedFileName)
                try {
                    destFile.writeBytes(compressedBytes)
                } catch (e: Exception) {
                    throw PosException(
                        PosErrorCode.INTERNAL_ERROR,
                        HttpStatusCode.InternalServerError,
                        "Failed to write image file: ${e.message}",
                        cause = e,
                    )
                }

                val imageUrl = "api/products/images/$savedFileName"
                logger.info(
                    "Product image uploaded: $savedFileName (${bytes.size / 1024}KB -> ${compressedBytes.size / 1024}KB) by user $userId",
                )
                call.respond(HttpStatusCode.OK, ProductDto.ImageUploadResponseDto(imageUrl = imageUrl))
            }
        }
    }

    // Public endpoint — no auth required so Coil can load images directly
    route("/api/products") {
        /**
         * Download a product image.
         *
         * Description: Public and unauthenticated on purpose, so image loaders on the POS and admin clients can fetch it directly. Filenames containing path separators or `..` are rejected to prevent traversal outside the image directory.
         *
         * Tag: products
         * Path: filename Stored image filename, as returned by the upload endpoint.
         * Response: 400 filename is missing, blank, or contains a path separator.
         * Response: 404 No stored image with that filename.
         */
        get("/images/{filename}") {
            val filename = call.parameters["filename"]
            if (filename.isNullOrBlank()) {
                throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Filename is required")
            }

            // Prevent path traversal
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                throw PosException(PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, "Invalid filename")
            }

            val storagePath = configManager.getDocumentStoragePath()
            val imageFile = File(File(storagePath, "product-images"), filename)

            if (!imageFile.exists()) {
                call.respond(HttpStatusCode.NotFound, "Image not found")
                return@get
            }

            call.respondFile(imageFile)
        }
    }
}
