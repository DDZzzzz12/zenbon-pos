package org.example.project.core.data.provider

import org.example.project.core.data.models.ChoiceGroup

/**
 * Read-only data provider for choice groups.
 */
interface ChoiceGroupDataProvider {
    /** Gets the resolved choice groups (with options) attached to a product. */
    suspend fun getGroupsForProduct(productId: Long): List<ChoiceGroup>

    /** Gets all choice groups (with options). */
    suspend fun getAllGroups(): List<ChoiceGroup>

    /** Gets the set of product IDs that have at least one choice group attached. */
    suspend fun getProductIdsWithGroups(): Set<Long>
}
