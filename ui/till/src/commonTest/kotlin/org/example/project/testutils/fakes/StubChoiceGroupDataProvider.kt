package org.example.project.testutils.fakes

import org.example.project.core.data.models.ChoiceGroup
import org.example.project.core.data.provider.ChoiceGroupDataProvider

/** Empty choice groups: for tests that only need a TableOrderViewModel to exist. */
class StubChoiceGroupDataProvider : ChoiceGroupDataProvider {
    override suspend fun getGroupsForProduct(productId: Long): List<ChoiceGroup> = emptyList()

    override suspend fun getAllGroups(): List<ChoiceGroup> = emptyList()

    override suspend fun getProductIdsWithGroups(): Set<Long> = emptySet()
}
