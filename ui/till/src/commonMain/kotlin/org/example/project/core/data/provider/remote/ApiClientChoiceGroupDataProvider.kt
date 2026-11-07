package org.example.project.core.data.provider.remote

import org.example.project.core.data.models.ChoiceGroup
import org.example.project.core.data.provider.ChoiceGroupDataProvider
import org.example.project.core.data.remote.ChoiceGroupApiClient
import org.example.project.core.data.remote.ProductChoiceGroupApiClient

/**
 * Remote data provider that delegates to the choice-group API clients.
 */
class ApiClientChoiceGroupDataProvider(
    private val choiceGroupApiClient: ChoiceGroupApiClient,
    private val productChoiceGroupApiClient: ProductChoiceGroupApiClient,
) : ChoiceGroupDataProvider {
    override suspend fun getGroupsForProduct(productId: Long): List<ChoiceGroup> =
        productChoiceGroupApiClient.getForProduct(productId).groups.map { ChoiceGroup.fromDto(it) }

    override suspend fun getAllGroups(): List<ChoiceGroup> = choiceGroupApiClient.getAll().items.map { ChoiceGroup.fromDto(it) }

    override suspend fun getProductIdsWithGroups(): Set<Long> =
        productChoiceGroupApiClient
            .getAll()
            .items
            .map { it.productId }
            .toSet()
}
