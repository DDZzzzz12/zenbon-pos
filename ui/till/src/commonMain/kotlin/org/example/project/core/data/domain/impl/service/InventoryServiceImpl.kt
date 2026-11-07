package org.example.project.core.data.domain.impl.service

import co.touchlab.kermit.Logger
import org.example.project.core.data.database.ProductCategoryDataManager
import org.example.project.core.data.database.ProductDataManager
import org.example.project.core.data.database.ProductExtraDataManager
import org.example.project.core.data.database.ProductExtrasAssignmentDataManager
import org.example.project.core.data.domain.service.InventoryService
import org.example.project.core.data.models.Product
import org.example.project.core.data.models.ProductCategory
import org.example.project.core.data.models.ProductExtra
import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.data.remote.ProductExtrasAssignmentApiClient
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.money.Decimal

class InventoryServiceImpl(
    private val productApiClient: ProductApiClient,
    private val productCategoryApiClient: ProductCategoryApiClient,
    private val productExtrasApiClient: ProductExtrasApiClient,
    private val productExtrasAssignmentApiClient: ProductExtrasAssignmentApiClient,
    // The four local-cache writers, or null on clients that keep no local cache — the wasm
    // browser preview builds no `PosDatabase`, so its data providers read from the server on every
    // call and there is nothing to write through to. Null therefore means "no cache to update",
    // never "skip the server": every method below still performs its API call unconditionally, and
    // only the cache write that follows it is elided. Mirrors the optional `SyncDataManager` on
    // `SettingManager`.
    private val productDataManager: ProductDataManager? = null,
    private val productCategoryDataManager: ProductCategoryDataManager? = null,
    private val productExtraDataManager: ProductExtraDataManager? = null,
    private val productExtrasAssignmentDataManager: ProductExtrasAssignmentDataManager? = null,
) : InventoryService {
    private val logger = Logger.withTag("InventoryService")

    override suspend fun syncAllFromServer() {
        logger.i { "Starting full inventory sync from server" }
        syncCategoriesFromServer()
        syncExtrasFromServer()
        syncProductsFromServer()
        syncProductExtrasAssignmentsFromServer()
        logger.i { "Full inventory sync completed successfully" }
    }

    override suspend fun syncProductsFromServer() {
        logger.i { "Syncing products from server" }
        productDataManager?.clearAllProducts()
        val products = productApiClient.getAllProducts()
        products.forEach { product ->
            if (product.productType == "VOUCHER") return@forEach
            productDataManager?.insertOrUpdateProduct(
                id = product.id,
                productNumber = product.productNumber,
                name = product.name,
                enName = product.en,
                chName = product.ch,
                description = product.description,
                // Read the server's decimal string exactly. The previous parse route still went
                // through a Double and pinned the scale at 2; Decimal.parse does neither.
                price = Decimal.parse(product.price),
                productType = product.productType,
                categoryId = product.categoryId.toLong(),
                isActive = if (product.isActive) 1L else 0L,
                allowCustomPrice = if (product.allowCustomPrice) 1L else 0L,
                imageUrl = product.imageUrl,
            )
        }
        logger.i { "Synced ${products.size} products" }
    }

    override suspend fun syncCategoriesFromServer() {
        logger.i { "Syncing categories from server" }
        productCategoryDataManager?.clearAllProductCategories()
        val categories = productCategoryApiClient.getAllProductCategories()
        categories.forEach { category ->
            if (category.name == "VOUCHER") return@forEach
            productCategoryDataManager?.insertOrUpdateProductCategory(
                id = category.id.toLong(),
                name = category.name,
                enName = category.en,
                chName = category.ch,
                description = category.description,
                isActive = if (category.isActive) 1L else 0L,
                sortPosition = category.sortPosition,
            )
        }
        logger.i { "Synced ${categories.size} categories" }
    }

    override suspend fun syncExtrasFromServer() {
        logger.i { "Syncing extras from server" }
        productExtraDataManager?.clearAllProductExtras()
        val extras = productExtrasApiClient.getAllProductExtras()
        extras.forEach { extra ->
            productExtraDataManager?.insertOrUpdateProductExtra(
                id = extra.id.toLong(),
                name = extra.name,
                enName = extra.en,
                chName = extra.ch,
                description = extra.description,
                price = Decimal.parse(extra.price),
                isActive = if (extra.isActive) 1L else 0L,
            )
        }
        logger.i { "Synced ${extras.size} extras" }
    }

    override suspend fun syncProductExtrasAssignmentsFromServer() {
        logger.i { "Syncing product-extras assignments from server" }
        productExtrasAssignmentDataManager?.deleteAll()
        val assignments = productExtrasAssignmentApiClient.getAll()
        assignments.items.forEach { assignment ->
            productExtrasAssignmentDataManager?.insertOrReplace(
                productId = assignment.productId,
                extrasId = assignment.extrasId.toLong(),
                updatedAt = assignment.updatedAt,
            )
        }
        logger.i { "Synced ${assignments.items.size} product-extras assignments" }
    }

    override suspend fun syncProductToServer(product: Product) {
        logger.i { "Syncing product ${product.name} to server" }
        val responseDto =
            if (product.id == 0L) {
                productApiClient.createProduct(
                    ProductDto.CreateProductRequestDto(
                        productNumber = product.productNumber,
                        name = product.name,
                        en = product.enName,
                        ch = product.chName,
                        description = product.description,
                        price = product.price.toPlainString(),
                        categoryId = product.categoryId.toInt(),
                        productType = product.productType,
                        isActive = product.isActive,
                        allowCustomPrice = product.allowCustomPrice,
                        imageUrl = product.imageUrl,
                    ),
                )
            } else {
                productApiClient.updateProduct(
                    id = product.id,
                    request =
                        ProductDto.UpdateProductRequestDto(
                            productNumber = product.productNumber,
                            name = product.name,
                            en = product.enName,
                            ch = product.chName,
                            description = product.description,
                            price = product.price.toPlainString(),
                            categoryId = product.categoryId.toInt(),
                            productType = product.productType,
                            isActive = product.isActive,
                            allowCustomPrice = product.allowCustomPrice,
                            imageUrl = product.imageUrl,
                        ),
                )
            }
        logger.i { "Product synced successfully" }
        val saved = Product.fromDto(responseDto)
        productDataManager?.insertOrUpdateProduct(
            id = saved.id,
            productNumber = saved.productNumber,
            name = saved.name,
            enName = saved.enName,
            chName = saved.chName,
            description = saved.description,
            price = saved.price,
            productType = saved.productType,
            categoryId = saved.categoryId,
            isActive = if (saved.isActive) 1L else 0L,
            allowCustomPrice = if (saved.allowCustomPrice) 1L else 0L,
            imageUrl = saved.imageUrl,
        )
    }

    override suspend fun syncCategoryToServer(category: ProductCategory) {
        logger.i { "Syncing category ${category.name} to server" }
        val responseDto =
            if (category.id == 0L) {
                productCategoryApiClient.createProductCategory(
                    ProductCategoryDto.CreateProductCategoryRequestDto(
                        name = category.name,
                        en = category.enName,
                        ch = category.chName,
                        description = category.description,
                        isActive = category.isActive,
                        sortOrder = category.sortPosition,
                    ),
                )
            } else {
                productCategoryApiClient.updateProductCategory(
                    id = category.id,
                    request =
                        ProductCategoryDto.UpdateProductCategoryRequestDto(
                            name = category.name,
                            en = category.enName,
                            ch = category.chName,
                            description = category.description,
                            isActive = category.isActive,
                        ),
                )
            }
        logger.i { "Category synced successfully" }
        val saved = ProductCategory.fromDto(responseDto)
        productCategoryDataManager?.insertOrUpdateProductCategory(
            id = saved.id,
            name = saved.name,
            enName = saved.enName,
            chName = saved.chName,
            description = saved.description,
            isActive = if (saved.isActive) 1L else 0L,
            sortPosition = saved.sortPosition,
        )
    }

    override suspend fun syncExtraToServer(extra: ProductExtra) {
        logger.i { "Syncing extra ${extra.name} to server" }
        val responseDto =
            if (extra.id <= 0L) {
                productExtrasApiClient.createProductExtra(
                    ProductExtrasDto.CreateProductExtrasRequestDto(
                        name = extra.name,
                        en = extra.enName,
                        ch = extra.chName,
                        description = extra.description,
                        price = extra.price.toPlainString(),
                        isActive = extra.isActive,
                    ),
                )
            } else {
                productExtrasApiClient.updateProductExtra(
                    id = extra.id,
                    request =
                        ProductExtrasDto.UpdateProductExtrasRequestDto(
                            name = extra.name,
                            description = extra.description,
                            price = extra.price.toPlainString(),
                            isActive = extra.isActive,
                        ),
                )
            }
        logger.i { "Extra synced successfully" }
        val saved = ProductExtra.fromDto(responseDto)
        productExtraDataManager?.insertOrUpdateProductExtra(
            id = saved.id,
            name = saved.name,
            enName = saved.enName,
            chName = saved.chName,
            description = saved.description,
            price = saved.price,
            isActive = if (saved.isActive) 1L else 0L,
        )
    }

    override suspend fun deleteProductOnServer(productId: Long) {
        logger.i { "Deleting product $productId from server" }
        val deleteStatus = productApiClient.deleteProduct(productId)
        logger.i { "Product deleted successfully" }
        // Both branches only rewrite the local cache, so with no cache there is nothing to do —
        // the server-side delete above has already happened either way.
        val cache = productDataManager ?: return
        if (deleteStatus == "INACTIVE") {
            val product = Product.fromDatabase(cache.getProductById(productId)!!)
            cache.insertOrUpdateProduct(
                id = product.id,
                productNumber = product.productNumber,
                name = product.name,
                enName = product.enName,
                chName = product.chName,
                description = product.description,
                price = product.price,
                productType = product.productType,
                categoryId = product.categoryId,
                isActive = 0L,
                allowCustomPrice = if (product.allowCustomPrice) 1L else 0L,
                imageUrl = product.imageUrl,
            )
        } else {
            cache.deleteProduct(productId)
        }
    }

    override suspend fun deleteCategoryOnServer(categoryId: Long) {
        logger.i { "Deleting category $categoryId from server" }
        productCategoryApiClient.deleteProductCategory(categoryId)
        logger.i { "Category deleted successfully" }
        productCategoryDataManager?.deleteProductCategory(categoryId)
    }

    override suspend fun deleteExtraOnServer(extraId: Long) {
        logger.i { "Deleting extra $extraId from server" }
        productExtrasApiClient.deleteProductExtra(extraId)
        logger.i { "Extra deleted successfully" }
        productExtraDataManager?.deleteProductExtra(extraId)
    }

    override suspend fun assignExtrasToProduct(request: ProductExtrasAssignmentDto.AssignRequestDto): List<ProductExtrasAssignmentDto.ResponseDto> {
        logger.i { "Assigning extras ${request.extrasIds} to product ${request.productId} on server" }
        val response = productExtrasAssignmentApiClient.assignToProduct(request)
        logger.i { "Extras assigned to product successfully" }
        response.forEach { assignment ->
            productExtrasAssignmentDataManager?.insertOrReplace(
                productId = assignment.productId,
                extrasId = assignment.extrasId.toLong(),
                updatedAt = assignment.updatedAt,
            )
        }
        return response
    }

    override suspend fun removeExtrasFromProduct(
        productId: Long,
        extrasId: Int,
    ) {
        logger.i { "Removing extra $extrasId from product $productId on server" }
        productExtrasAssignmentApiClient.removeFromProduct(productId, extrasId)
        logger.i { "Extra removed from product successfully" }
        productExtrasAssignmentDataManager?.deleteByProductAndExtra(productId, extrasId.toLong())
    }

    override suspend fun bulkAssignExtrasToCategory(
        request: ProductExtrasAssignmentDto.BulkCategoryAssignRequestDto,
    ): ProductExtrasAssignmentDto.BulkAssignResultDto {
        logger.i { "Bulk assigning extras ${request.extrasIds} to category ${request.categoryId} on server" }
        val response = productExtrasAssignmentApiClient.bulkAssignToCategory(request)
        logger.i { "Bulk extras assigned successfully" }
        syncProductExtrasAssignmentsFromServer()
        return response
    }

    override suspend fun bulkRemoveExtrasFromCategory(
        categoryId: Int,
        extrasId: Int,
    ) {
        logger.i { "Bulk removing extra $extrasId from category $categoryId on server" }
        productExtrasAssignmentApiClient.bulkRemoveFromCategory(categoryId, extrasId)
        logger.i { "Bulk extra removed successfully" }
        syncProductExtrasAssignmentsFromServer()
    }

    override suspend fun updateCategoryPositions(categoryIds: List<Pair<Long, Double>>) {
        logger.i { "Updating ${categoryIds.size} category positions on server" }
        val request =
            categoryIds.map { (id, sortPosition) ->
                ProductCategoryDto.UpdateProductCategoryPositionRequestDto(
                    categoryId = id.toInt(),
                    sortOrder = sortPosition,
                )
            }
        productCategoryApiClient.updateCategoryPositions(request)
        logger.i { "Category positions updated successfully" }
        categoryIds.forEach { (id, sortPosition) ->
            productCategoryDataManager?.updateSortPositionById(sortPosition, id)
        }
    }

    override suspend fun importProductsCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto {
        logger.i { "Importing products from CSV: $fileName (${csvContent.size} bytes), overwrite=$overwrite" }
        return productApiClient.importProductsCsv(csvContent, fileName, overwrite)
    }

    override suspend fun importCategoriesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto {
        logger.i { "Importing categories from CSV: $fileName (${csvContent.size} bytes), overwrite=$overwrite" }
        return productCategoryApiClient.importCategoriesCsv(csvContent, fileName, overwrite)
    }

    override suspend fun importExtrasCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto {
        logger.i { "Importing extras from CSV: $fileName (${csvContent.size} bytes), overwrite=$overwrite" }
        return productExtrasApiClient.importExtrasCsv(csvContent, fileName, overwrite)
    }

    override suspend fun uploadProductImage(
        imageBytes: ByteArray,
        fileName: String,
    ): ProductDto.ImageUploadResponseDto {
        logger.i { "Uploading product image: $fileName (${imageBytes.size} bytes)" }
        return productApiClient.uploadProductImage(imageBytes, fileName)
    }
}
