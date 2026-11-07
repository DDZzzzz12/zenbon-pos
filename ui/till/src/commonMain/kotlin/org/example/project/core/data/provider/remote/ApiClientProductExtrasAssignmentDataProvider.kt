package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.ProductExtrasAssignment
import org.example.project.core.data.provider.ProductExtrasAssignmentDataProvider
import org.example.project.core.data.remote.ProductExtrasAssignmentApiClient

/**
 * Remote data provider that delegates to [ProductExtrasAssignmentApiClient] for product-extras reads.
 *
 * Stage C Batch 9: flipped to throws-style.
 */
class ApiClientProductExtrasAssignmentDataProvider(
    private val apiClient: ProductExtrasAssignmentApiClient,
) : ProductExtrasAssignmentDataProvider {
    override suspend fun getExtrasForProduct(productId: Long): List<Int> = apiClient.getForProduct(productId).map { it.extrasId }

    override suspend fun getAllAssignments(): List<ProductExtrasAssignment> = apiClient.getAll().items.map { ProductExtrasAssignment.fromDto(it) }
}
