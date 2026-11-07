package org.example.project.core.data.domain.service

import org.example.project.core.data.models.Product
import org.example.project.core.data.models.ProductCategory
import org.example.project.core.data.models.ProductExtra
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.product.ProductExtrasAssignmentDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

/**
 * Service interface for inventory management operations.
 *
 * Provides methods for syncing inventory data between local database
 * and remote server, including products, categories, extras, and assignments.
 *
 * All methods throw PosApiException/NetworkApiException on failure;
 * callers wrap in runCatchingApi.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
interface InventoryService {
    suspend fun syncAllFromServer()

    suspend fun syncProductsFromServer()

    suspend fun syncCategoriesFromServer()

    suspend fun syncExtrasFromServer()

    suspend fun syncProductExtrasAssignmentsFromServer()

    suspend fun syncProductToServer(product: Product)

    suspend fun syncCategoryToServer(category: ProductCategory)

    suspend fun syncExtraToServer(extra: ProductExtra)

    suspend fun deleteProductOnServer(productId: Long)

    suspend fun deleteCategoryOnServer(categoryId: Long)

    suspend fun deleteExtraOnServer(extraId: Long)

    suspend fun assignExtrasToProduct(request: ProductExtrasAssignmentDto.AssignRequestDto): List<ProductExtrasAssignmentDto.ResponseDto>

    suspend fun removeExtrasFromProduct(
        productId: Long,
        extrasId: Int,
    )

    suspend fun bulkAssignExtrasToCategory(request: ProductExtrasAssignmentDto.BulkCategoryAssignRequestDto): ProductExtrasAssignmentDto.BulkAssignResultDto

    suspend fun bulkRemoveExtrasFromCategory(
        categoryId: Int,
        extrasId: Int,
    )

    suspend fun updateCategoryPositions(categoryIds: List<Pair<Long, Double>>)

    suspend fun importProductsCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean = false,
    ): InitialSetupDto.CsvImportResultDto

    suspend fun importCategoriesCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean = false,
    ): InitialSetupDto.CsvImportResultDto

    suspend fun importExtrasCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean = false,
    ): InitialSetupDto.CsvImportResultDto

    suspend fun uploadProductImage(
        imageBytes: ByteArray,
        fileName: String,
    ): ProductDto.ImageUploadResponseDto
}
