package org.example.project.features.product.data.repository

import org.example.project.features.product.database.tables.ProductChoiceGroupTable
import org.example.project.features.product.domain.repository.ProductChoiceGroupRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class ProductChoiceGroupRepositoryImpl : ProductChoiceGroupRepository {
    override fun getGroupIdsForProduct(productId: Long): List<Int> =
        ProductChoiceGroupTable
            .selectAll()
            .where { ProductChoiceGroupTable.productId eq productId }
            .orderBy(ProductChoiceGroupTable.sortPosition to SortOrder.ASC)
            .map { it[ProductChoiceGroupTable.groupId].value }

    override fun getAll(): List<ResultRow> = ProductChoiceGroupTable.selectAll().toList()

    override fun getUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<ResultRow> =
        ProductChoiceGroupTable
            .selectAll()
            .where { ProductChoiceGroupTable.updatedAt greater lastSyncTime }
            .toList()

    override fun replaceForProduct(
        productId: Long,
        groupIdsInOrder: List<Int>,
    ) {
        ProductChoiceGroupTable.deleteWhere { ProductChoiceGroupTable.productId eq productId }
        groupIdsInOrder.forEachIndexed { index, gid ->
            ProductChoiceGroupTable.insert {
                it[ProductChoiceGroupTable.productId] = productId
                it[groupId] = gid
                it[sortPosition] = index
                it[updatedAt] = currentTimeStamp()
            }
        }
    }

    override fun attachIfNotExists(
        productId: Long,
        groupId: Int,
    ): Boolean {
        val exists =
            ProductChoiceGroupTable
                .selectAll()
                .where { (ProductChoiceGroupTable.productId eq productId) and (ProductChoiceGroupTable.groupId eq groupId) }
                .any()
        if (exists) return false

        val nextPosition =
            ProductChoiceGroupTable
                .selectAll()
                .where { ProductChoiceGroupTable.productId eq productId }
                .maxOfOrNull { it[ProductChoiceGroupTable.sortPosition] }
                ?.plus(1) ?: 0

        ProductChoiceGroupTable.insert {
            it[ProductChoiceGroupTable.productId] = productId
            it[ProductChoiceGroupTable.groupId] = groupId
            it[sortPosition] = nextPosition
            it[updatedAt] = currentTimeStamp()
        }
        return true
    }

    override fun delete(
        productId: Long,
        groupId: Int,
    ) {
        ProductChoiceGroupTable.deleteWhere {
            (ProductChoiceGroupTable.productId eq productId) and (ProductChoiceGroupTable.groupId eq groupId)
        }
    }
}
