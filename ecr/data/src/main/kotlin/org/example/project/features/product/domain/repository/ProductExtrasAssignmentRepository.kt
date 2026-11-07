package org.example.project.features.product.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.product.domain.models.ProductExtrasAssignment

interface ProductExtrasAssignmentRepository {
    fun getAll(includeInactive: Boolean? = null): List<ProductExtrasAssignment>

    fun getByProductId(productId: Long): List<ProductExtrasAssignment>

    fun create(
        productId: Long,
        extrasId: Int,
    ): ProductExtrasAssignment

    fun createIfNotExists(
        productId: Long,
        extrasId: Int,
    ): ProductExtrasAssignment?

    /**
     * Detaches one extra from one product.
     *
     * @return false when no such assignment existed. Not-found is the caller's decision to make (a
     *   404), not an exception here: a repository never throws for an absent row.
     */
    fun delete(
        productId: Long,
        extrasId: Int,
    ): Boolean

    fun deleteByProductIds(
        productIds: List<Long>,
        extrasId: Int,
    )

    fun getUpdatedAfter(lastSyncTime: LocalDateTime): List<ProductExtrasAssignment>
}
