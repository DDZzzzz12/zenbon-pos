package org.example.project.admin.features.product

import org.example.project.core.data.remote.ProductApiClient
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.dto.setup.InitialSetupDto

/** Hand-written fake; records mutating calls so tests can assert on them. */
class FakeProductApiClient(
    var products: List<ProductDto.ProductResponseDto> = emptyList(),
    private val onError: (() -> Nothing)? = null,
) : ProductApiClient {
    var created: ProductDto.CreateProductRequestDto? = null
    var updatedId: Long? = null
    var updated: ProductDto.UpdateProductRequestDto? = null
    var deletedId: Long? = null
    var uploadedFileName: String? = null

    override suspend fun getAllProducts(
        isActive: Boolean?,
        categoryId: Long?,
        limit: Int?,
        offset: Int?,
    ): List<ProductDto.ProductResponseDto> {
        onError?.invoke()
        return products
    }

    override suspend fun getProductById(id: Long) = products.first { it.id == id }

    override suspend fun getProductByNumber(productNumber: String) = products.first { it.productNumber == productNumber }

    override suspend fun getProductsByCategory(categoryId: Long) = products.filter { it.categoryId.toLong() == categoryId }

    override suspend fun searchProducts(
        query: String,
        limit: Int?,
    ) = products.filter { it.name.contains(query, true) }

    override suspend fun createProduct(request: ProductDto.CreateProductRequestDto): ProductDto.ProductResponseDto {
        created = request
        return ProductDto.ProductResponseDto(
            99,
            request.productNumber,
            request.name,
            request.en,
            request.ch,
            request.description,
            request.price,
            request.categoryId,
            "cat",
            request.productType,
            request.isActive,
            request.allowCustomPrice,
            request.imageUrl,
        )
    }

    override suspend fun updateProduct(
        id: Long,
        request: ProductDto.UpdateProductRequestDto,
    ): ProductDto.ProductResponseDto {
        updatedId = id
        updated = request
        return ProductDto.ProductResponseDto(
            id,
            request.productNumber,
            request.name,
            request.en,
            request.ch,
            request.description,
            request.price,
            request.categoryId,
            "cat",
            request.productType,
            request.isActive,
            request.allowCustomPrice,
            request.imageUrl,
        )
    }

    override suspend fun deleteProduct(id: Long): String {
        deletedId = id
        return "deleted"
    }

    override suspend fun syncProducts(lastSyncTime: String) = products

    override suspend fun importProductsCsv(
        csvContent: ByteArray,
        fileName: String,
        overwrite: Boolean,
    ) = InitialSetupDto.CsvImportResultDto(0, 0, 0, dataType = "products", success = true, message = "")

    override suspend fun uploadProductImage(
        imageBytes: ByteArray,
        fileName: String,
    ): ProductDto.ImageUploadResponseDto {
        uploadedFileName = fileName
        return ProductDto.ImageUploadResponseDto("api/products/images/$fileName")
    }

    override suspend fun getFavouriteProductIds(): List<Long> = emptyList()
}
