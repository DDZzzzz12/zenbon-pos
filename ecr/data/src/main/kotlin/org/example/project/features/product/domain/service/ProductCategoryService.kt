/**
 * Service interface for ProductCategory business operations.
 *
 * Migrated to throws-style in Stage B Batch 10. Methods return values directly
 * and throw [org.example.project.infrastructure.errors.PosException] on errors.
 */
package org.example.project.features.product.domain.service

import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

interface ProductCategoryService {
    suspend fun getAllCategories(includeInactive: Boolean = false): List<ProductCategoryDto.ProductCategoryResponseDto>

    suspend fun getCategoriesByVisibility(visibilityScopes: List<String>): List<ProductCategoryDto.ProductCategoryResponseDto>

    suspend fun getCategoryById(id: Int): ProductCategoryDto.ProductCategoryResponseDto

    suspend fun getActiveCategories(): List<ProductCategoryDto.ProductCategoryResponseDto>

    suspend fun createCategory(
        employeeId: String,
        request: ProductCategoryDto.CreateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto

    suspend fun updateCategory(
        employeeId: String,
        id: Int,
        request: ProductCategoryDto.UpdateProductCategoryRequestDto,
    ): ProductCategoryDto.ProductCategoryResponseDto

    suspend fun deleteCategory(
        employeeId: String,
        id: Int,
    )

    suspend fun getCategoriesUpdatedAfter(lastSyncTime: String): List<ProductCategoryDto.ProductCategoryResponseDto>

    suspend fun updateCategoryPosition(
        categoryId: Long,
        newSortPosition: Double,
        employeeId: String,
    )

    suspend fun importCategoriesCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto
}
