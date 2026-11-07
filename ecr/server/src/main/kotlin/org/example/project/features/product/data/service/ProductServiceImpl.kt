/**
 * Implementation of ProductService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.product.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.SystemRecords
import org.example.project.core.websocket.NotificationService
import org.example.project.features.pfand.domain.models.PfandType
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.repository.ProductRepository
import org.example.project.features.product.domain.service.ProductService
import org.example.project.features.product.mapper.toResponse
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.SystemRecordGuard
import org.example.project.utils.builder.CsvReader
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.io.File

class ProductServiceImpl(
    private val repository: ProductRepository,
    private val configManager: IShopConfigurationManager,
) : ProductService {
    private val logger = LoggerFactory.getLogger(ProductServiceImpl::class.java)

    private suspend fun deleteImageFile(imageUrl: String?) {
        if (imageUrl == null) return
        if (!imageUrl.startsWith("api/products/images/")) return
        try {
            val fileName = imageUrl.removePrefix("api/products/images/")
            val storagePath = configManager.getDocumentStoragePath()
            val file = File(File(storagePath, "product-images"), fileName)
            if (file.exists()) {
                file.delete()
                logger.info("deleteImageFile: deleted image={}", file.absolutePath)
            }
        } catch (e: Exception) {
            logger.warn("deleteImageFile: failed to delete image={}", imageUrl, e)
        }
    }

    override suspend fun getAllProducts(
        limit: Int?,
        offset: Int,
        active: Boolean?,
    ): List<ProductDto.ProductResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            // Ordering comes from SQL (ProductRepositoryImpl: ORDER BY category_id, id). Do NOT
            // re-add a .sortedBy here — sorting after the repository applied LIMIT/OFFSET only sorts
            // the current page and hides an unstable page boundary.
            repository.getAllProducts(limit, offset, active).map { it.toResponse(currency) }
        }
    }

    override suspend fun getProductsByCategoryVisibility(visibilityScopes: List<String>): List<ProductDto.ProductResponseDto> {
        logger.info("getProductsByCategoryVisibility: scopes=$visibilityScopes")
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getProductsByCategoryVisibility(visibilityScopes).map { it.toResponse(currency) }
        }
    }

    override suspend fun getProductById(id: Long): ProductDto.ProductResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val product = repository.getProductById(id) ?: throw productNotFound(id)
            product.toResponse(currency)
        }
    }

    override suspend fun getProductByNumber(productNumber: String): ProductDto.ProductResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val product =
                repository.getProductByNumber(productNumber)
                    ?: throw PosException(
                        PosErrorCode.PRODUCT_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Product '$productNumber' not found",
                    )
            product.toResponse(currency)
        }
    }

    override suspend fun getProductsByCategory(
        categoryId: Int,
        limit: Int?,
        offset: Int,
    ): List<ProductDto.ProductResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getProductsByCategory(categoryId, limit, offset).map { it.toResponse(currency) }
        }
    }

    override suspend fun getProductsByType(
        productType: ProductType,
        limit: Int?,
        offset: Int,
    ): List<ProductDto.ProductResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getProductsByType(productType, limit, offset).map { it.toResponse(currency) }
        }
    }

    override suspend fun searchProductsByName(
        searchTerm: String,
        limit: Int?,
        offset: Int,
        includeInactive: Boolean,
    ): List<ProductDto.ProductResponseDto> {
        if (searchTerm.isBlank()) throw validationFailed("searchTerm is required")
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.searchProductsByName(searchTerm, limit, offset, includeInactive).map { it.toResponse(currency) }
        }
    }

    override suspend fun getActiveProducts(
        limit: Int?,
        offset: Int,
    ): List<ProductDto.ProductResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getActiveProducts(limit, offset).map { it.toResponse(currency) }
        }
    }

    override suspend fun getActiveProductsByCategory(
        categoryId: Int,
        limit: Int?,
        offset: Int,
    ): List<ProductDto.ProductResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository.getActiveProductsByCategory(categoryId, limit, offset).map { it.toResponse(currency) }
        }
    }

    override suspend fun createProduct(
        employeeId: String,
        request: ProductDto.CreateProductRequestDto,
    ): ProductDto.ProductResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createProduct(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Created product '${dto.name}'")
                    },
                ),
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                val price = requireValidPrice(request.price)
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }
                requireActivePfandType(request.pfandTypeId)

                val product =
                    repository.createProduct(
                        productNumber = request.productNumber.trim().uppercase(),
                        name = request.name.trim(),
                        enName = request.en,
                        chName = request.ch,
                        description = request.description?.trim(),
                        price = price,
                        categoryId = request.categoryId,
                        productType = ProductType.valueOf(request.productType),
                        isActive = request.isActive,
                        allowCustomPrice = request.allowCustomPrice,
                        imageUrl = request.imageUrl,
                        pfandTypeId = request.pfandTypeId,
                    )

                val response = product.toResponse(currency)
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT,
                    DataChangeOperation.CREATED,
                    product.id.value,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun updateProduct(
        employeeId: String,
        id: Long,
        request: ProductDto.UpdateProductRequestDto,
    ): ProductDto.ProductResponseDto =
        ExceptionConverter.catchingThrows(
            context = "updateProduct(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.name, "Updated product '${dto.name}'")
                    },
                ),
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                val price = requireValidPrice(request.price)
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }
                val existingProduct = repository.getProductById(id) ?: throw productNotFound(id)
                requireNotSystemProduct(existingProduct, "changed")
                requirePfandLinkChangeAllowed(existingProduct, request.pfandTypeId)
                val oldImageUrl = existingProduct.imageUrl

                val product =
                    repository.updateProduct(
                        id = id,
                        productNumber = request.productNumber.trim().uppercase(),
                        name = request.name.trim(),
                        enName = request.en,
                        chName = request.ch,
                        description = request.description?.trim(),
                        price = price,
                        categoryId = request.categoryId,
                        productType = ProductType.valueOf(request.productType),
                        isActive = request.isActive,
                        allowCustomPrice = request.allowCustomPrice,
                        imageUrl = request.imageUrl,
                        pfandTypeId = request.pfandTypeId,
                    ) ?: throw productNotFound(id)

                if (oldImageUrl != null && oldImageUrl != request.imageUrl) {
                    deleteImageFile(oldImageUrl)
                }

                val response = product.toResponse(currency)
                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT,
                    DataChangeOperation.UPDATED,
                    id,
                    excludeEmployeeId = employeeId,
                )
                response
            }
        }

    override suspend fun deleteProduct(
        employeeId: String,
        id: Long,
    ): String {
        // The repository answers the OUTCOME ("DELETED" / "INACTIVE"), which the route returns; the
        // audit row must name the product, so the name is captured before the row disappears
        // (admin21 NOTE-b21: a hard delete was audited as "Deleted product 'DELETED'").
        var productName = "#$id"
        return ExceptionConverter.catchingThrows(
            context = "deleteProduct(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRODUCT,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { outcome -> deleteLogData(id, productName, outcome) },
                ),
        ) {
            dbQuery {
                val existingProduct = repository.getProductById(id) ?: throw productNotFound(id)
                productName = existingProduct.name
                requireNotSystemProduct(existingProduct, "deleted")
                val oldImageUrl = existingProduct.imageUrl

                val name = repository.deleteProduct(id)

                if (name == "DELETED" && oldImageUrl != null) {
                    deleteImageFile(oldImageUrl)
                }

                NotificationService.notifyDataChange(
                    ChangeEntityType.PRODUCT,
                    DataChangeOperation.DELETED,
                    id,
                    excludeEmployeeId = employeeId,
                )
                name
            }
        }
    }

    override suspend fun getProductsUpdatedAfter(lastSyncTime: String): List<ProductDto.ProductResponseDto> {
        val parsedDateTime =
            try {
                LocalDateTime.parse(lastSyncTime)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("lastSyncTime must be ISO datetime (e.g., 2024-12-01T10:30:00)")
            }
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            repository
                .getProductsUpdatedAfter(parsedDateTime)
                .filter { it.productType != ProductType.VOUCHER }
                .map { it.toResponse(currency) }
        }
    }

    override suspend fun importProductsCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto {
        logger.info("importProductsCsv: starting CSV import for products")

        val csvData = CsvReader.parseCsvBytes(csvContent, delimiter = ';', hasHeader = true)
        if (csvData.isEmpty()) throw validationFailed("CSV file is empty or could not be parsed")

        var successCount = 0
        var errorCount = 0
        var systemSkipped = 0
        val errors = mutableListOf<String>()

        csvData.forEachIndexed { index, rowMap ->
            val rowNumber = index + 2
            try {
                val skippedBefore = systemSkipped
                importProductRow(rowMap, overwrite) { systemSkipped++ }?.let { error ->
                    errors.add("Row $rowNumber: $error")
                    errorCount++
                } ?: run { if (systemSkipped == skippedBefore) successCount++ }
            } catch (e: Exception) {
                logger.error("importProductsCsv: error processing row $rowNumber: ${e.message}", e)
                errors.add("Row $rowNumber: ${e.message ?: "Unknown error"}")
                errorCount++
            }
        }

        try {
            dbQuery {
                TransactionManager.current().exec(
                    "SELECT setval('product_id_seq', COALESCE((SELECT MAX(id) FROM product), 1), true)",
                )
            }
            logger.info("importProductsCsv: reset sequence for PRODUCT after CSV import")
        } catch (e: Exception) {
            logger.warn("importProductsCsv: failed to reset sequence for PRODUCT: ${e.message}")
        }

        val skippedCount = csvData.size - successCount - errorCount
        return InitialSetupDto.CsvImportResultDto(
            totalRows = csvData.size,
            successCount = successCount,
            errorCount = errorCount,
            skippedCount = skippedCount,
            errors = errors,
            dataType = "PRODUCT",
            success = errorCount == 0,
            message = buildImportMessage(successCount, errorCount, skippedCount),
        )
    }

    /** [onSystemSkip] is called when an overwrite row targets a system product, which is left untouched and counts as skipped. */
    private suspend fun importProductRow(
        rowMap: Map<String, String>,
        overwrite: Boolean,
        onSystemSkip: () -> Unit,
    ): String? {
        return try {
            val id = rowMap["id"]?.toLongOrNull()
            val productNumber = rowMap["product_number"] ?: return "Missing 'product_number' column"
            val name = rowMap["name"] ?: return "Missing 'name' column"
            val enName = rowMap["en_name"]?.takeIf { it.isNotBlank() }
            val chName = rowMap["ch_name"]?.takeIf { it.isNotBlank() }
            val description = rowMap["description"]?.takeIf { it.isNotBlank() }
            val price = rowMap["price"]?.toBigDecimalOrNull() ?: return "Invalid 'price' value"
            val categoryId = rowMap["category_id"]?.toIntOrNull() ?: return "Invalid 'category_id' value"
            // No default: the type drives the VAT/DSFinV-K mapping, so a missing one must be chosen
            // explicitly, as in the product editor (FO-54) — a silent FOOD booked drinks as food.
            val rawType = rowMap["product_type"]?.trim()
            if (rawType.isNullOrEmpty()) {
                return "Missing 'product_type' value (one of ${productTypeNames()})"
            }
            val productType =
                ProductType.entries.firstOrNull { it.name.equals(rawType, ignoreCase = true) }
                    ?: return "Invalid 'product_type' value '$rawType' (one of ${productTypeNames()})"
            val isActive = rowMap["is_active"]?.toBoolean() ?: true

            dbQuery {
                if (overwrite) {
                    val targetId = id ?: throw IllegalArgumentException("Missing 'id' column")
                    val existing = repository.getProductById(targetId)
                    if (existing != null && SystemRecords.isSystemProduct(existing.productType.name)) {
                        logger.info("importProductRow: skipping system product id=$targetId")
                        onSystemSkip()
                        return@dbQuery
                    }
                    repository.updateProduct(
                        id = targetId,
                        productNumber = productNumber,
                        name = name,
                        enName = enName,
                        chName = chName,
                        description = description,
                        price = price,
                        categoryId = categoryId,
                        productType = productType,
                        isActive = isActive,
                    )
                } else {
                    repository.createProduct(
                        productNumber = productNumber,
                        name = name,
                        enName = enName,
                        chName = chName,
                        description = description,
                        price = price,
                        categoryId = categoryId,
                        productType = productType,
                        isActive = isActive,
                    )
                }
                Unit
            }
            null
        } catch (e: IllegalStateException) {
            if (e.message?.contains("Duplicate") == true) {
                logger.info("importProductRow: product already exists, skipping: ${e.message}")
                null
            } else {
                e.message
            }
        } catch (e: Exception) {
            e.message
        }
    }

    private fun productTypeNames(): String = ProductType.entries.joinToString(", ") { it.name }

    private fun buildImportMessage(
        successCount: Int,
        errorCount: Int,
        skippedCount: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (successCount > 0) parts.add("$successCount imported")
        if (skippedCount > 0) parts.add("$skippedCount skipped (duplicates)")
        if (errorCount > 0) parts.add("$errorCount failed")
        return if (parts.isEmpty()) "No products processed" else "PRODUCT: ${parts.joinToString(", ")}"
    }

    /** Audit row of a product delete: names the product, and says when it was only deactivated. */
    private fun deleteLogData(
        id: Long,
        productName: String,
        outcome: String,
    ): EntryLogData =
        if (outcome == "INACTIVE") {
            EntryLogData(id.toString(), productName, "Deactivated product '$productName' (still referenced by orders or promotions)")
        } else {
            EntryLogData(id.toString(), productName, "Deleted product '$productName'")
        }

    /** Parses a wire price, refusing anything that is not a non-negative decimal with [PosErrorCode.PRICE_INVALID]. */
    private fun requireValidPrice(raw: String): java.math.BigDecimal {
        val price = raw.trim().toBigDecimalOrNull()
        if (price == null || price.signum() < 0) {
            throw PosException(PosErrorCode.PRICE_INVALID, HttpStatusCode.BadRequest, "Price must be a non-negative decimal number")
        }
        return price
    }

    /** Refuses to change or delete the voucher article ([SystemRecords]); the voucher bootstrap provisions it directly. */
    private fun requireNotSystemProduct(
        product: Product,
        action: String,
    ) {
        if (SystemRecords.isSystemProduct(product.productType.name)) {
            throw SystemRecordGuard.protectedRecord("Product", product.name, action)
        }
    }

    private fun productNotFound(id: Long) =
        PosException(
            PosErrorCode.PRODUCT_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Product $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    /**
     * Must run inside a dbQuery transaction. The update-side Pfand-link guard.
     *
     * - A **backing** product (the deposit charge or the return a Pfand type seeded) belongs to its
     *   type for good: `PfandTypeServiceImpl` finds it again by `pfandTypeId` + flag to reprice or
     *   deactivate it, so an edit that re-points or clears the link would orphan it and make the
     *   next repricing seed a duplicate. The link must come back unchanged.
     * - An **unchanged** link is always accepted, even to a type deactivated since: keeping an
     *   existing assignment is not assigning, and refusing it would make every later edit of the
     *   product fail until the operator clears a field they never touched.
     * - Any **new** link must name an active type, exactly as on create.
     */
    private fun requirePfandLinkChangeAllowed(
        existing: Product,
        requestedPfandTypeId: Long?,
    ) {
        val currentPfandTypeId = existing.pfandType?.id?.value
        if (existing.isDeposit || existing.isDepositReturn) {
            if (requestedPfandTypeId != currentPfandTypeId) {
                throw validationFailed("A Pfand type's own deposit/return product cannot be moved to another Pfand type")
            }
            return
        }
        if (requestedPfandTypeId == currentPfandTypeId) return
        requireActivePfandType(requestedPfandTypeId)
    }

    /** Must run inside a dbQuery transaction. Rejects an unknown or inactive Pfand-type link. */
    private fun requireActivePfandType(pfandTypeId: Long?) {
        if (pfandTypeId == null) return
        val type = PfandType.findById(pfandTypeId)
        if (type == null || !type.isActive) {
            throw validationFailed("pfandTypeId points to an unknown or inactive Pfand type")
        }
    }
}
