package org.example.project.core.data.provider.local

import org.example.project.core.data.database.ChoiceGroupDataManager
import org.example.project.core.data.database.ChoiceGroupOptionDataManager
import org.example.project.core.data.database.ProductChoiceGroupDataManager
import org.example.project.core.data.database.ProductExtraDataManager
import org.example.project.core.data.models.ChoiceGroup
import org.example.project.core.data.models.ChoiceOption
import org.example.project.core.data.provider.ChoiceGroupDataProvider

/**
 * Local data provider that assembles [ChoiceGroup]s from the three local choice tables,
 * resolving option names from [local_product_extras].
 */
class LocalChoiceGroupDataProvider(
    private val choiceGroupDataManager: ChoiceGroupDataManager,
    private val choiceGroupOptionDataManager: ChoiceGroupOptionDataManager,
    private val productChoiceGroupDataManager: ProductChoiceGroupDataManager,
    private val productExtraDataManager: ProductExtraDataManager,
) : ChoiceGroupDataProvider {
    override suspend fun getGroupsForProduct(productId: Long): List<ChoiceGroup> =
        productChoiceGroupDataManager
            .getByProduct(productId)
            .mapNotNull { mapping -> buildGroup(mapping.group_id) }

    override suspend fun getAllGroups(): List<ChoiceGroup> = choiceGroupDataManager.getAll().map { group -> buildGroup(group.id)!! }

    override suspend fun getProductIdsWithGroups(): Set<Long> = productChoiceGroupDataManager.getProductIdsWithGroups()

    private fun buildGroup(groupId: Long): ChoiceGroup? {
        val group = choiceGroupDataManager.getById(groupId)?.takeIf { it.is_active == 1L } ?: return null
        val options =
            choiceGroupOptionDataManager.getByGroup(groupId).mapNotNull { row ->
                // A deleted extra (removed locally on its DELETE change, or synced inactive) is no
                // longer offered: drop it instead of showing a nameless option the server would
                // refuse on order (admin21 NOTE-c21).
                val extra = productExtraDataManager.getProductExtraById(row.option_id)?.takeIf { it.is_active == 1L } ?: return@mapNotNull null
                ChoiceOption(
                    optionId = row.option_id.toInt(),
                    name = extra.name,
                    enName = extra.en_name,
                    chName = extra.ch_name,
                    priceDelta = row.price_delta,
                    sortPosition = row.sort_position.toInt(),
                    isDefault = row.is_default == 1L,
                    linkedProductId = extra.linked_product_id,
                )
            }
        return ChoiceGroup(
            id = group.id.toInt(),
            name = group.name,
            enName = group.en_name,
            chName = group.ch_name,
            minSelections = group.min_selections.toInt(),
            maxSelections = group.max_selections.toInt(),
            options = options,
        )
    }
}
