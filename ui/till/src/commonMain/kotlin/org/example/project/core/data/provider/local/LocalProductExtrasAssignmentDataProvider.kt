package org.example.project.core.data.provider.local

import org.example.project.core.data.database.ProductExtrasAssignmentDataManager
import org.example.project.core.data.models.ProductExtrasAssignment
import org.example.project.core.data.provider.ProductExtrasAssignmentDataProvider

/**
 * Local data provider wrapping [ProductExtrasAssignmentDataManager] for product-extras assignment reads.
 *
 * Stage C Batch 9: flipped to throws-style.
 */
class LocalProductExtrasAssignmentDataProvider(
    private val dataManager: ProductExtrasAssignmentDataManager,
) : ProductExtrasAssignmentDataProvider {
    override suspend fun getExtrasForProduct(productId: Long): List<Int> = dataManager.getByProductId(productId).map { it.extrasId.toInt() }

    override suspend fun getAllAssignments(): List<ProductExtrasAssignment> = dataManager.getAll().map { ProductExtrasAssignment.fromDatabase(it) }
}
