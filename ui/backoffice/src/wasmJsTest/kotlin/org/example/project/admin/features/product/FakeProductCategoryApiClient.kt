package org.example.project.admin.features.product

import org.example.project.core.data.remote.ProductCategoryApiClient
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

class FakeProductCategoryApiClient(
    var categories: List<ProductCategoryDto.ProductCategoryResponseDto> = emptyList(),
) : ProductCategoryApiClient {
    var created: ProductCategoryDto.CreateProductCategoryRequestDto? = null
    var updatedId: Long? = null
    var updated: ProductCategoryDto.UpdateProductCategoryRequestDto? = null
    var deletedId: Long? = null

    override suspend fun getAllProductCategories(
        isActive: Boolean?,
        limit: Int?,
        offset: Int?,
    ) = categories

    override suspend fun getProductCategoryById(id: Long) = categories.first { it.id.toLong() == id }

    override suspend fun createProductCategory(request: ProductCategoryDto.CreateProductCategoryRequestDto): ProductCategoryDto.ProductCategoryResponseDto {
        created = request
        return ProductCategoryDto.ProductCategoryResponseDto(
            50,
            request.name,
            request.en,
            request.ch,
            request.description,
            request.isActive,
            request.sortOrder,
            "2026-07-11",
            request.visibility,
        )
    }

    override suspend fun updateProductCategory(
        id: Long,
        request: ProductCategoryDto.UpdateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto {
        updatedId = id
        updated = request
        return ProductCategoryDto.ProductCategoryResponseDto(
            id.toInt(),
            request.name,
            request.en,
            request.ch,
            request.description,
            request.isActive,
            0.0,
            "2026-07-11",
            request.visibility ?: "EVERYWHERE",
        )
    }

    override suspend fun deleteProductCategory(id: Long) {
        deletedId = id
    }

    override suspend fun syncProductCategories(lastSyncTime: String) = categories

    override suspend fun updateCategoryPositions(updates: List<ProductCategoryDto.UpdateProductCategoryPositionRequestDto>) {}

    override suspend fun importCategoriesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ) = InitialSetupDto.CsvImportResultDto(0, 0, 0, dataType = "categories", success = true, message = "")
}
