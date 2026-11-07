/**
 * API client interface for product operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

interface ProductApiClient {
    suspend fun getAllProducts(
        isActive: Boolean? = null,
        categoryId: Long? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<ProductDto.ProductResponseDto>

    suspend fun getProductById(id: Long): ProductDto.ProductResponseDto

    suspend fun getProductByNumber(productNumber: String): ProductDto.ProductResponseDto

    suspend fun getProductsByCategory(categoryId: Long): List<ProductDto.ProductResponseDto>

    suspend fun searchProducts(
        query: String,
        limit: Int? = null,
    ): List<ProductDto.ProductResponseDto>

    suspend fun createProduct(request: ProductDto.CreateProductRequestDto): ProductDto.ProductResponseDto

    suspend fun updateProduct(
        id: Long,
        request: ProductDto.UpdateProductRequestDto,
    ): ProductDto.ProductResponseDto

    suspend fun deleteProduct(id: Long): String

    suspend fun syncProducts(lastSyncTime: String): List<ProductDto.ProductResponseDto>

    suspend fun importProductsCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean = false,
    ): InitialSetupDto.CsvImportResultDto

    suspend fun uploadProductImage(
        imageBytes: ByteArray,
        fileName: String,
    ): ProductDto.ImageUploadResponseDto

    suspend fun getFavouriteProductIds(): List<Long>
}
