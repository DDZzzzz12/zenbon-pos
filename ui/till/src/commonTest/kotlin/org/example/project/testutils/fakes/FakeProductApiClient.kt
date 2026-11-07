package org.example.project.testutils.fakes

import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

/**
 * Stage C Batch 7: throws-style fake. Configure failures by setting any
 * `*Result` to a `kotlin.Result.failure(...)` (typically a
 * [org.example.project.core.network.error.PosApiException] /
 * [org.example.project.core.network.error.NetworkApiException]).
 */
class FakeProductApiClient : ProductApiClient {
    private val emptyProduct =
        ProductDto.ProductResponseDto(
            id = 0,
            productNumber = "",
            name = "",
            description = null,
            price = "0.00",
            categoryId = 0,
            categoryName = "",
            productType = "FOOD",
            isActive = true,
        )

    var getAllProductsResult: Result<List<ProductDto.ProductResponseDto>> = Result.success(emptyList())
    var getProductByIdResult: Result<ProductDto.ProductResponseDto> = Result.success(emptyProduct)
    var getProductByNumberResult: Result<ProductDto.ProductResponseDto> = Result.success(emptyProduct)
    var getProductsByCategoryResult: Result<List<ProductDto.ProductResponseDto>> = Result.success(emptyList())
    var searchProductsResult: Result<List<ProductDto.ProductResponseDto>> = Result.success(emptyList())
    var createProductResult: Result<ProductDto.ProductResponseDto> = Result.success(emptyProduct)
    var updateProductResult: Result<ProductDto.ProductResponseDto> = Result.success(emptyProduct)
    var deleteProductResult: Result<String> = Result.success("Deleted")
    var syncProductsResult: Result<List<ProductDto.ProductResponseDto>> = Result.success(emptyList())
    var importProductsCsvResult: Result<InitialSetupDto.CsvImportResultDto> =
        Result.success(
            InitialSetupDto.CsvImportResultDto(
                totalRows = 0,
                successCount = 0,
                errorCount = 0,
                errors = emptyList(),
                dataType = "products",
                success = true,
                message = "",
            ),
        )
    var uploadProductImageResult: Result<ProductDto.ImageUploadResponseDto> =
        Result.success(
            ProductDto.ImageUploadResponseDto(imageUrl = "http://example.com/image.png"),
        )
    var getFavouriteProductIdsResult: Result<List<Long>> = Result.success(emptyList())

    var getAllProductsCallCount = 0
    var deleteProductCallCount = 0

    /** Last request DTOs, so tests can assert on the exact wire values a ViewModel built. */
    var lastCreateRequest: ProductDto.CreateProductRequestDto? = null
    var lastUpdateRequest: ProductDto.UpdateProductRequestDto? = null

    override suspend fun getAllProducts(
        isActive: Boolean?,
        categoryId: Long?,
        limit: Int?,
        offset: Int?,
    ): List<ProductDto.ProductResponseDto> {
        getAllProductsCallCount++
        return getAllProductsResult.getOrThrow()
    }

    override suspend fun getProductById(id: Long): ProductDto.ProductResponseDto = getProductByIdResult.getOrThrow()

    override suspend fun getProductByNumber(productNumber: String): ProductDto.ProductResponseDto = getProductByNumberResult.getOrThrow()

    override suspend fun getProductsByCategory(categoryId: Long): List<ProductDto.ProductResponseDto> = getProductsByCategoryResult.getOrThrow()

    override suspend fun searchProducts(
        query: String,
        limit: Int?,
    ): List<ProductDto.ProductResponseDto> = searchProductsResult.getOrThrow()

    override suspend fun createProduct(request: ProductDto.CreateProductRequestDto): ProductDto.ProductResponseDto {
        lastCreateRequest = request
        return createProductResult.getOrThrow()
    }

    override suspend fun updateProduct(
        id: Long,
        request: ProductDto.UpdateProductRequestDto,
    ): ProductDto.ProductResponseDto {
        lastUpdateRequest = request
        return updateProductResult.getOrThrow()
    }

    override suspend fun deleteProduct(id: Long): String {
        deleteProductCallCount++
        return deleteProductResult.getOrThrow()
    }

    override suspend fun syncProducts(lastSyncTime: String): List<ProductDto.ProductResponseDto> = syncProductsResult.getOrThrow()

    override suspend fun importProductsCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto = importProductsCsvResult.getOrThrow()

    override suspend fun uploadProductImage(
        imageBytes: ByteArray,
        fileName: String,
    ): ProductDto.ImageUploadResponseDto = uploadProductImageResult.getOrThrow()

    override suspend fun getFavouriteProductIds(): List<Long> = getFavouriteProductIdsResult.getOrThrow()
}
