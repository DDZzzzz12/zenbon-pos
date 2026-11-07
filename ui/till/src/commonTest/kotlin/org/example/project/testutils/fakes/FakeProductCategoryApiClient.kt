package org.example.project.testutils.fakes

import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

/**
 * Stage C Batch 7: throws-style fake.
 */
class FakeProductCategoryApiClient : ProductCategoryApiClient {
    private val emptyDto =
        ProductCategoryDto.ProductCategoryResponseDto(
            id = 0,
            name = "",
            description = null,
            isActive = true,
            sortPosition = 0.0,
            updatedAt = "",
        )

    var getAllProductCategoriesResult: Result<List<ProductCategoryDto.ProductCategoryResponseDto>> = Result.success(emptyList())
    var getProductCategoryByIdResult: Result<ProductCategoryDto.ProductCategoryResponseDto> = Result.success(emptyDto)
    var createProductCategoryResult: Result<ProductCategoryDto.ProductCategoryResponseDto> = Result.success(emptyDto)
    var updateProductCategoryResult: Result<ProductCategoryDto.ProductCategoryResponseDto> = Result.success(emptyDto)
    var deleteProductCategoryResult: Result<Unit> = Result.success(Unit)
    var syncProductCategoriesResult: Result<List<ProductCategoryDto.ProductCategoryResponseDto>> = Result.success(emptyList())
    var updateCategoryPositionsResult: Result<Unit> = Result.success(Unit)
    var importCategoriesCsvResult: Result<InitialSetupDto.CsvImportResultDto> =
        Result.success(
            InitialSetupDto.CsvImportResultDto(
                totalRows = 0,
                successCount = 0,
                errorCount = 0,
                errors = emptyList(),
                dataType = "categories",
                success = true,
                message = "",
            ),
        )

    var getAllProductCategoriesCallCount = 0

    override suspend fun getAllProductCategories(
        isActive: Boolean?,
        limit: Int?,
        offset: Int?,
    ): List<ProductCategoryDto.ProductCategoryResponseDto> {
        getAllProductCategoriesCallCount++
        return getAllProductCategoriesResult.getOrThrow()
    }

    override suspend fun getProductCategoryById(id: Long): ProductCategoryDto.ProductCategoryResponseDto = getProductCategoryByIdResult.getOrThrow()

    override suspend fun createProductCategory(request: ProductCategoryDto.CreateProductCategoryRequestDto): ProductCategoryDto.ProductCategoryResponseDto =
        createProductCategoryResult.getOrThrow()

    override suspend fun updateProductCategory(
        id: Long,
        request: ProductCategoryDto.UpdateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto = updateProductCategoryResult.getOrThrow()

    override suspend fun deleteProductCategory(id: Long) {
        deleteProductCategoryResult.getOrThrow()
    }

    override suspend fun syncProductCategories(lastSyncTime: String): List<ProductCategoryDto.ProductCategoryResponseDto> =
        syncProductCategoriesResult.getOrThrow()

    override suspend fun updateCategoryPositions(updates: List<ProductCategoryDto.UpdateProductCategoryPositionRequestDto>) {
        updateCategoryPositionsResult.getOrThrow()
    }

    override suspend fun importCategoriesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto = importCategoriesCsvResult.getOrThrow()
}
