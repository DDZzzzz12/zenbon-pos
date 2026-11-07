/**
 * Service interface for ProductExtras business operations.
 *
 * Migrated to throws-style in Stage B Batch 10. Methods return values directly
 * and throw [org.example.project.infrastructure.errors.PosException] on errors.
 */
package org.example.project.features.product.domain.service

import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

interface ProductExtrasService {
    suspend fun getAllExtras(includeInactive: Boolean = false): List<ProductExtrasDto.ProductExtrasResponseDto>

    suspend fun getExtraById(id: Int): ProductExtrasDto.ProductExtrasResponseDto

    suspend fun getActiveExtras(): List<ProductExtrasDto.ProductExtrasResponseDto>

    suspend fun getExtrasByCategory(categoryId: Int): List<ProductExtrasDto.ProductExtrasResponseDto>

    suspend fun createExtra(
        employeeId: String,
        request: ProductExtrasDto.CreateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto

    suspend fun updateExtra(
        employeeId: String,
        id: Int,
        request: ProductExtrasDto.UpdateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto

    suspend fun deleteExtra(
        employeeId: String,
        id: Int,
    ): String

    suspend fun getExtrasUpdatedAfter(lastSyncTime: String): List<ProductExtrasDto.ProductExtrasResponseDto>

    suspend fun importExtrasCsv(
        csvContent: ByteArray,
        overwrite: Boolean,
    ): InitialSetupDto.CsvImportResultDto
}
