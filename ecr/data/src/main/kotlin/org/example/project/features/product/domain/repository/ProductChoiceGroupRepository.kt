package org.example.project.features.product.domain.repository

import org.jetbrains.exposed.v1.core.ResultRow

interface ProductChoiceGroupRepository {
    fun getGroupIdsForProduct(productId: Long): List<Int> // ordered by sort_position

    fun getAll(): List<ResultRow>

    fun getUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<ResultRow>

    fun replaceForProduct(
        productId: Long,
        groupIdsInOrder: List<Int>,
    )

    /**
     * Attaches [groupId] to [productId] if not already present, appending at the next sort_position.
     * Returns true if a row was inserted, false if it already existed.
     */
    fun attachIfNotExists(
        productId: Long,
        groupId: Int,
    ): Boolean

    /** Removes the (productId, groupId) attachment row. */
    fun delete(
        productId: Long,
        groupId: Int,
    )
}
