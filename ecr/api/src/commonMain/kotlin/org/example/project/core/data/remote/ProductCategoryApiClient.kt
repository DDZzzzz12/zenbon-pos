/**
 * API client interface for product category operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

interface ProductCategoryApiClient {
    suspend fun getAllProductCategories(
        isActive: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<ProductCategoryDto.ProductCategoryResponseDto>

    suspend fun getProductCategoryById(id: Long): ProductCategoryDto.ProductCategoryResponseDto

    suspend fun createProductCategory(request: ProductCategoryDto.CreateProductCategoryRequestDto): ProductCategoryDto.ProductCategoryResponseDto

    suspend fun updateProductCategory(
        id: Long,
        request: ProductCategoryDto.UpdateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto

    suspend fun deleteProductCategory(id: Long)

    suspend fun syncProductCategories(lastSyncTime: String): List<ProductCategoryDto.ProductCategoryResponseDto>

    suspend fun updateCategoryPositions(updates: List<ProductCategoryDto.UpdateProductCategoryPositionRequestDto>)

    suspend fun importCategoriesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean = false,
    ): InitialSetupDto.CsvImportResultDto
}
