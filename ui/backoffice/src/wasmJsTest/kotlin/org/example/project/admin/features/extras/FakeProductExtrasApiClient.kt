package org.example.project.admin.features.extras

import org.example.project.core.data.remote.ProductExtrasApiClient
import org.example.project.core.domain.dto.product.ProductExtrasDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

class FakeProductExtrasApiClient(
    var extras: List<ProductExtrasDto.ProductExtrasResponseDto> = emptyList(),
    private val onError: (() -> Nothing)? = null,
) : ProductExtrasApiClient {
    var created: ProductExtrasDto.CreateProductExtrasRequestDto? = null
    var updatedId: Long? = null
    var updated: ProductExtrasDto.UpdateProductExtrasRequestDto? = null
    var deletedId: Long? = null

    override suspend fun getAllProductExtras(
        isActive: Boolean?,
        limit: Int?,
        offset: Int?,
    ): List<ProductExtrasDto.ProductExtrasResponseDto> {
        onError?.invoke()
        return extras
    }

    override suspend fun getProductExtraById(id: Long) = extras.first { it.id.toLong() == id }

    override suspend fun createProductExtra(request: ProductExtrasDto.CreateProductExtrasRequestDto): ProductExtrasDto.ProductExtrasResponseDto {
        created = request
        return ProductExtrasDto.ProductExtrasResponseDto(
            77,
            request.name,
            request.en,
            request.ch,
            request.description,
            request.price,
            request.isActive,
        )
    }

    override suspend fun updateProductExtra(
        id: Long,
        request: ProductExtrasDto.UpdateProductExtrasRequestDto,
    ): ProductExtrasDto.ProductExtrasResponseDto {
        updatedId = id
        updated = request
        return ProductExtrasDto.ProductExtrasResponseDto(
            id.toInt(),
            request.name,
            request.en,
            request.ch,
            request.description,
            request.price,
            request.isActive,
        )
    }

    override suspend fun deleteProductExtra(id: Long) {
        deletedId = id
    }

    override suspend fun syncProductExtras(lastSyncTime: String) = extras

    override suspend fun importExtrasCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ) = InitialSetupDto.CsvImportResultDto(0, 0, 0, dataType = "extras", success = true, message = "")
}
