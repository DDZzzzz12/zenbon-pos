/**
 * REST API routes for Shop Information operations.
 *
 * Throws PosException directly on validation/auth errors; the global StatusPages
 * plugin emits application/problem+json responses. Service throws PosException
 * directly (Stage B Batch 4) — no Result matching here.
 */
package org.example.project.features.setup.domain.routes

import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.setup.domain.service.ShopInfoService
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.plugins.getEmployeeIdFromJwt
import org.example.project.utils.ImageUtils
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

private val logger = LoggerFactory.getLogger("ShopInfoRoutes")

/**
 * Configures shop information API routes.
 */
fun Route.shopInfoRoutes() {
    val service by inject<ShopInfoService>()
    val configManager by inject<IShopConfigurationManager>()

    authenticate("auth-jwt") {
        route("/api/shop-info") {
            /**
             * List shop information records.
             *
             * Description: Requires ADMIN role. Returns the full record for every shop, including the tax and registration details the sync variant withholds.
             *
             * Tag: shop-info
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get {
                call.respond(HttpStatusCode.OK, service.getAllShopInfo())
            }

            /**
             * Get one shop's information.
             *
             * Description: Requires ADMIN role. The full record, including sensitive fiscal identifiers.
             *
             * Tag: shop-info
             * Path: id Shop identifier. Must be a positive number.
             * Response: 400 id is missing, or is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No shop information for that id.
             */
            get("/{id}") {
                val shopId = parseShopId(call.parameters["id"])
                val shopInfo = service.getShopInfoById(shopId)
                if (shopInfo != null) {
                    call.respond(HttpStatusCode.OK, shopInfo)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Shop information not found for shop ID: $shopId")
                }
            }

            /**
             * Get a shop's public details for sync.
             *
             * Description: Open to any authenticated employee, unlike the ADMIN-only reads beside it, because every till needs the shop's name, address and mode to print a receipt. The payload deliberately omits the fiscal and registration fields. The `shopId` query parameter selects the shop and defaults to 1.
             *
             * Tag: shop-info
             * Response: 401 Missing or invalid bearer token.
             * Response: 404 No shop information for that shop id.
             */
            get("/sync") {
                val shopId = call.request.queryParameters["shopId"]?.toLongOrNull() ?: 1L
                val shopInfo = service.getShopInfoById(shopId)
                if (shopInfo != null) {
                    val syncDto =
                        org.example.project.core.domain.dto.setup.ShopInfoSyncDto.ShopInfoSyncResponseDto(
                            shopId = shopInfo.shopId,
                            shopName = shopInfo.shopName,
                            companyName = shopInfo.companyName,
                            shopAddress = shopInfo.shopAddress,
                            shopCity = shopInfo.shopCity,
                            zipCode = shopInfo.zipCode,
                            shopCountry = shopInfo.shopCountry,
                            shopPhone = shopInfo.shopPhone,
                            shopEmail = shopInfo.shopEmail,
                            shopTimezone = shopInfo.shopTimezone,
                            shopWebsite = shopInfo.shopWebsite,
                            restaurantMode = shopInfo.restaurantMode,
                            kioskMode = shopInfo.kioskMode,
                            logoUrl = shopInfo.logoUrl,
                        )
                    call.respond(HttpStatusCode.OK, syncDto)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Shop information not found for shop ID: $shopId")
                }
            }

            /**
             * Create a shop information record.
             *
             * Description: Requires ADMIN role. Normally the setup wizard writes this once; the route exists for multi-shop installs and for correcting a botched setup.
             *
             * Tag: shop-info
             * Response: 400 The request body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 409 Shop information for that shop already exists.
             */
            post {
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ShopInfoDto.CreateShopInfoRequestDto>()
                val response = service.createShopInfo(userId, request)
                call.respond(HttpStatusCode.Created, response)
            }

            /**
             * Update a shop's information.
             *
             * Description: Requires ADMIN role. Only the fields present in the body are changed. The address and tax number printed on receipts come from here, so a change is visible on the next printed document.
             *
             * Tag: shop-info
             * Path: id Shop identifier. Must be a positive number.
             * Response: 400 id is missing or not a positive number, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No shop information for that id.
             */
            put("/{id}") {
                val shopId = parseShopId(call.parameters["id"])
                val userId = call.getEmployeeIdFromJwt()
                val request = call.receive<ShopInfoDto.UpdateShopInfoRequestDto>()
                val response = service.updateShopInfo(userId, shopId, request)
                call.respond(HttpStatusCode.OK, response)
            }

            /**
             * Delete a shop information record.
             *
             * Description: Requires ADMIN role. Removing the record of a shop that has traded leaves receipts without their issuer details, so this is intended for cleaning up a mistaken multi-shop entry.
             *
             * Tag: shop-info
             * Path: id Shop identifier. Must be a positive number.
             * Response: 400 id is missing, or is not a positive number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No shop information for that id.
             */
            delete("/{id}") {
                val shopId = parseShopId(call.parameters["id"])
                val userId = call.getEmployeeIdFromJwt()
                service.deleteShopInfo(userId, shopId)
                call.respond(HttpStatusCode.OK, "Shop information deleted successfully")
            }

            /**
             * Upload the shop logo.
             *
             * Description: Requires ADMIN role. Send the image as a multipart upload. Accepts jpg, jpeg, png and webp up to 10 MB raw; the file is re-compressed to JPEG, stored under the configured document storage path, and shop 1's logo URL is updated to point at it. The logo is printed on receipts.
             *
             * Tag: shop-info
             * Response: 400 The request carried no image file part.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 413 The uploaded image exceeds the 10 MB limit.
             * Response: 415 The file extension is not jpg, jpeg, png or webp.
             * Response: 500 The image could not be compressed or written to disk.
             */
            post("/upload-logo") {
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

                if (imageBytes == null || originalFileName == null) {
                    throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "No image file provided",
                    )
                }

                val allowedExtensions = setOf("jpg", "jpeg", "png", "webp")
                val extension = originalFileName!!.substringAfterLast('.', "").lowercase()
                if (extension !in allowedExtensions) {
                    throw PosException(
                        PosErrorCode.IMAGE_TYPE_UNSUPPORTED,
                        HttpStatusCode.UnsupportedMediaType,
                        "Image type not supported: $extension. Allowed: $allowedExtensions",
                    )
                }

                val maxSize = 10 * 1024 * 1024 // 10MB raw upload limit
                if (imageBytes!!.size > maxSize) {
                    throw PosException(
                        PosErrorCode.IMAGE_TOO_LARGE,
                        HttpStatusCode.PayloadTooLarge,
                        "Image file too large. Maximum size: 10MB",
                    )
                }

                val compressedBytes =
                    try {
                        ImageUtils.compressImage(imageBytes!!)
                    } catch (e: Exception) {
                        logger.error("Failed to compress shop logo image", e)
                        throw PosException(
                            PosErrorCode.INTERNAL_ERROR,
                            HttpStatusCode.InternalServerError,
                            "Failed to process image: ${e.message}",
                            cause = e,
                        )
                    }

                val savedFileName =
                    try {
                        val storagePath = configManager.getDocumentStoragePath()
                        val imageDir = File(storagePath, "shop-logos")
                        imageDir.mkdirs()

                        val fileName = "${UUID.randomUUID()}.jpg"
                        val destFile = File(imageDir, fileName)
                        destFile.writeBytes(compressedBytes)
                        fileName
                    } catch (e: Exception) {
                        logger.error("Failed to write shop logo to disk", e)
                        throw PosException(
                            PosErrorCode.INTERNAL_ERROR,
                            HttpStatusCode.InternalServerError,
                            "Failed to save logo file: ${e.message}",
                            cause = e,
                        )
                    }

                val logoUrl = "api/shop-info/logo/$savedFileName"
                logger.info(
                    "Shop logo uploaded: $savedFileName (${imageBytes!!.size / 1024}KB -> ${compressedBytes.size / 1024}KB) by user $userId",
                )

                // Update shop's logoUrl in the database
                val updateRequest = ShopInfoDto.UpdateShopInfoRequestDto(logoUrl = logoUrl)
                service.updateShopInfo(userId, 1L, updateRequest)

                call.respond(HttpStatusCode.OK, ShopInfoDto.LogoUploadResponseDto(logoUrl = logoUrl))
            }
        }
    }

    // Public endpoint — no auth required so Coil can load images directly
    route("/api/shop-info") {
        /**
         * Download the shop logo.
         *
         * Description: Public and unauthenticated on purpose, so image loaders and the login screen can fetch it before anyone signs in. Filenames containing path separators or `..` are rejected to prevent traversal outside the logo directory.
         *
         * Tag: shop-info
         * Path: filename Stored logo filename, as returned by the upload endpoint.
         * Response: 400 filename is missing, blank, or contains a path separator.
         * Response: 404 No stored logo with that filename.
         */
        get("/logo/{filename}") {
            val filename = call.parameters["filename"]
            if (filename.isNullOrBlank()) {
                throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.BadRequest,
                    "Filename is required",
                )
            }

            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                throw PosException(
                    PosErrorCode.INVALID_REQUEST,
                    HttpStatusCode.BadRequest,
                    "Invalid filename",
                )
            }

            val storagePath = configManager.getDocumentStoragePath()
            val imageFile = File(File(storagePath, "shop-logos"), filename)

            if (!imageFile.exists()) {
                call.respond(HttpStatusCode.NotFound, "Logo not found")
                return@get
            }

            call.respondFile(imageFile)
        }
    }
}

private fun parseShopId(raw: String?): Long {
    if (raw == null) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Missing shop ID parameter",
        )
    }
    val id = raw.toLongOrNull()
    if (id == null || id <= 0) {
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Invalid shop ID format. Must be a positive number.",
        )
    }
    return id
}
