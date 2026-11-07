/**
 * Service interface for Product business operations.
 *
 * Migrated to throws-style in Stage B Batch 10. Methods return values directly
 * and throw [org.example.project.infrastructure.errors.PosException] on errors.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.domain.service

import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.product.ProductType

interface ProductService {
    suspend fun getAllProducts(
        limit: Int? = null,
        offset: Int = 0,
        active: Boolean? = null,
    ): List<ProductDto.ProductResponseDto>

    suspend fun getProductsByCategoryVisibility(visibilityScopes: List<String>): List<ProductDto.ProductResponseDto>

    suspend fun getProductById(id: Long): ProductDto.ProductResponseDto

    suspend fun getProductByNumber(productNumber: String): ProductDto.ProductResponseDto

    suspend fun getProductsByCategory(
        categoryId: Int,
        limit: Int? = null,
        offset: Int = 0,
    ): List<ProductDto.ProductResponseDto>

    suspend fun getProductsByType(
        productType: ProductType,
        limit: Int? = null,
        offset: Int = 0,
    ): List<ProductDto.ProductResponseDto>

    suspend fun searchProductsByName(
        searchTerm: String,
        limit: Int? = null,
        offset: Int = 0,
        includeInactive: Boolean = false,
    ): List<ProductDto.ProductResponseDto>

    suspend fun getActiveProducts(
        limit: Int? = null,
        offset: Int = 0,
    ): List<ProductDto.ProductResponseDto>

    suspend fun getActiveProductsByCategory(
        categoryId: Int,
        limit: Int? = null,
        offset: Int = 0,
    ): List<ProductDto.ProductResponseDto>

    suspend fun createProduct(
        employeeId: String,
        request: ProductDto.CreateProductRequestDto,
    ): ProductDto.ProductResponseDto

    suspend fun updateProduct(
        employeeId: String,
        id: Long,
        request: ProductDto.UpdateProductRequestDto,
    ): ProductDto.ProductResponseDto

    suspend fun deleteProduct(
        employeeId: String,
        id: Long,
    ): String

    suspend fun getProductsUpdatedAfter(lastSyncTime: String): List<ProductDto.ProductResponseDto>

    suspend fun importProductsCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto
}
