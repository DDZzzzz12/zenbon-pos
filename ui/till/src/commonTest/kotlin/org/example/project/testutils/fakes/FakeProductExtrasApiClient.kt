package org.example.project.testutils.fakes

import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

/**
 * Stage C Batch 7: throws-style fake.
 */
class FakeProductExtrasApiClient : ProductExtrasApiClient {
    private val emptyDto =
        ProductExtrasDto.ProductExtrasResponseDto(
            id = 0,
            name = "",
            description = null,
            price = "0.00",
            isActive = true,
        )

    var getAllProductExtrasResult: Result<List<ProductExtrasDto.ProductExtrasResponseDto>> = Result.success(emptyList())
    var getProductExtraByIdResult: Result<ProductExtrasDto.ProductExtrasResponseDto> = Result.success(emptyDto)
    var createProductExtraResult: Result<ProductExtrasDto.ProductExtrasResponseDto> = Result.success(emptyDto)
    var updateProductExtraResult: Result<ProductExtrasDto.ProductExtrasResponseDto> = Result.success(emptyDto)
    var deleteProductExtraResult: Result<Unit> = Result.success(Unit)
    var syncProductExtrasResult: Result<List<ProductExtrasDto.ProductExtrasResponseDto>> = Result.success(emptyList())
    var importExtrasCsvResult: Result<InitialSetupDto.CsvImportResultDto> =
        Result.success(
            InitialSetupDto.CsvImportResultDto(
                totalRows = 0,
                successCount = 0,
                errorCount = 0,
                errors = emptyList(),
                dataType = "extras",
                success = true,
                message = "",
            ),
        )

    var getAllProductExtrasCallCount = 0

    /** Last request DTOs, so tests can assert on the exact wire values a ViewModel built. */
    var lastCreateRequest: ProductExtrasDto.CreateProductExtrasRequestDto? = null
    var lastUpdateRequest: ProductExtrasDto.UpdateProductExtrasRequestDto? = null

    override suspend fun getAllProductExtras(
        isActive: Boolean?,
        limit: Int?,
        offset: Int?,
    ): List<ProductExtrasDto.ProductExtrasResponseDto> {
        getAllProductExtrasCallCount++
        return getAllProductExtrasResult.getOrThrow()
    }

    override suspend fun getProductExtraById(id: Long): ProductExtrasDto.ProductExtrasResponseDto = getProductExtraByIdResult.getOrThrow()

    override suspend fun createProductExtra(request: ProductExtrasDto.CreateProductExtrasRequestDto): ProductExtrasDto.ProductExtrasResponseDto {
        lastCreateRequest = request
        return createProductExtraResult.getOrThrow()
    }

    override suspend fun updateProductExtra(
        id: Long,
        request: ProductExtrasDto.UpdateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto {
        lastUpdateRequest = request
        return updateProductExtraResult.getOrThrow()
    }

    override suspend fun deleteProductExtra(id: Long) {
        deleteProductExtraResult.getOrThrow()
    }

    override suspend fun syncProductExtras(lastSyncTime: String): List<ProductExtrasDto.ProductExtrasResponseDto> = syncProductExtrasResult.getOrThrow()

    override suspend fun importExtrasCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto = importExtrasCsvResult.getOrThrow()
}
