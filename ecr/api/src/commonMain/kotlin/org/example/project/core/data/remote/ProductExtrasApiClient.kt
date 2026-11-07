/**
 * API client interface for product extras operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

interface ProductExtrasApiClient {
    suspend fun getAllProductExtras(
        isActive: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): List<ProductExtrasDto.ProductExtrasResponseDto>

    suspend fun getProductExtraById(id: Long): ProductExtrasDto.ProductExtrasResponseDto

    suspend fun createProductExtra(request: ProductExtrasDto.CreateProductExtrasRequestDto): ProductExtrasDto.ProductExtrasResponseDto

    suspend fun updateProductExtra(
        id: Long,
        request: ProductExtrasDto.UpdateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto

    suspend fun deleteProductExtra(id: Long)

    suspend fun syncProductExtras(lastSyncTime: String): List<ProductExtrasDto.ProductExtrasResponseDto>

    suspend fun importExtrasCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean = false,
    ): InitialSetupDto.CsvImportResultDto
}
