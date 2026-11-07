package org.example.project.features.product.data.repository

import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.example.project.features.product.database.tables.ProductChoiceGroupTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.domain.models.ChoiceGroup
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class ChoiceGroupRepositoryImpl : ChoiceGroupRepository {
    override fun getAll(includeInactive: Boolean): List<ChoiceGroup> =
        if (includeInactive) {
            ChoiceGroup.all().toList()
        } else {
            ChoiceGroup.find { ChoiceGroupTable.isActive eq true }.toList()
        }

    override fun getById(id: Int): ChoiceGroup? = ChoiceGroup.findById(id)

    override fun create(
        name: String,
        enName: String?,
        chName: String?,
        minSelections: Int,
        maxSelections: Int,
        isActive: Boolean,
    ): ChoiceGroup =
        ChoiceGroup.new {
            this.name = name
            this.enName = enName
            this.chName = chName
            this.minSelections = minSelections
            this.maxSelections = maxSelections
            this.isActive = isActive
            this.updatedAt = currentTimeStamp()
        }

    override fun update(
        id: Int,
        name: String,
        enName: String?,
        chName: String?,
        minSelections: Int,
        maxSelections: Int,
        isActive: Boolean,
    ): ChoiceGroup? {
        val g = ChoiceGroup.findById(id) ?: return null
        g.name = name
        g.enName = enName
        g.chName = chName
        g.minSelections = minSelections
        g.maxSelections = maxSelections
        g.isActive = isActive
        g.updatedAt = currentTimeStamp()
        return g
    }

    override fun softDelete(id: Int): String {
        val g = ChoiceGroup.findById(id) ?: throw NoSuchElementException("ChoiceGroup not found: $id")
        val n = g.name
        g.isActive = false
        g.updatedAt = currentTimeStamp()
        // The group is gone for good, so is every attachment to it: left behind, each product would
        // keep listing a group that no longer exists (an empty entry in the product editor).
        ProductChoiceGroupTable.deleteWhere { ProductChoiceGroupTable.groupId eq id }
        return n
    }

    override fun getUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<ChoiceGroup> =
        ChoiceGroup.find { ChoiceGroupTable.updatedAt greater lastSyncTime }.toList()

    override fun getOptions(groupId: Int): List<ResultRow> =
        ChoiceGroupOptionTable
            .selectAll()
            .where { ChoiceGroupOptionTable.groupId eq groupId }
            .orderBy(ChoiceGroupOptionTable.sortPosition to SortOrder.ASC)
            .toList()

    override fun replaceOptions(
        groupId: Int,
        options: List<ChoiceGroupRepository.OptionRow>,
    ) {
        ChoiceGroupOptionTable.deleteWhere { ChoiceGroupOptionTable.groupId eq groupId }
        options.forEach { opt ->
            ChoiceGroupOptionTable.insert {
                it[ChoiceGroupOptionTable.groupId] = groupId
                it[optionId] = opt.optionId
                it[priceDelta] = opt.priceDelta
                it[sortPosition] = opt.sortPosition
                it[isDefault] = opt.isDefault
                it[updatedAt] = currentTimeStamp()
            }
        }
    }

    override fun groupsBrokenByWithdrawingOption(optionId: Int): List<ChoiceGroup> {
        val groupIds = groupIdsPlacing(optionId)
        if (groupIds.isEmpty()) return emptyList()
        return ChoiceGroup
            .find { (ChoiceGroupTable.id inList groupIds) and (ChoiceGroupTable.isActive eq true) }
            .filter { it.minSelections > 0 }
            .filter { group -> offeredOptionCount(group.id.value, excluding = optionId) < group.minSelections }
            .sortedBy { it.name }
    }

    override fun touchGroupsContainingOption(optionId: Int): List<Int> {
        val groupIds = groupIdsPlacing(optionId)
        if (groupIds.isEmpty()) return emptyList()
        val now = currentTimeStamp()
        ChoiceGroupTable.update({ ChoiceGroupTable.id inList groupIds }) { it[updatedAt] = now }
        return groupIds
    }

    private fun groupIdsPlacing(optionId: Int): List<Int> =
        ChoiceGroupOptionTable
            .select(ChoiceGroupOptionTable.groupId)
            .where { ChoiceGroupOptionTable.optionId eq optionId }
            .map { it[ChoiceGroupOptionTable.groupId].value }
            .distinct()

    /** Options of [groupId] whose extra is active, not counting [excluding]. */
    private fun offeredOptionCount(
        groupId: Int,
        excluding: Int,
    ): Int =
        ChoiceGroupOptionTable
            .innerJoin(ProductExtrasTable)
            .select(ChoiceGroupOptionTable.optionId)
            .where {
                (ChoiceGroupOptionTable.groupId eq groupId) and
                    (ChoiceGroupOptionTable.optionId neq excluding) and
                    (ProductExtrasTable.isActive eq true)
            }.count()
            .toInt()
}
