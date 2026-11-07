package org.example.project.features.product.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.product.database.tables.ProductExtrasAssignmentTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.ProductExtrasAssignment
import org.example.project.features.product.domain.repository.ProductExtrasAssignmentRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class ProductExtrasAssignmentRepositoryImpl : ProductExtrasAssignmentRepository {
    override fun getAll(includeInactive: Boolean?): List<ProductExtrasAssignment> {
        val query =
            ProductExtrasAssignmentTable
                .innerJoin(ProductTable)
                .selectAll()

        if (includeInactive != true) {
            query.andWhere { ProductTable.isActive eq true }
        }

        return query.map { row ->
            ProductExtrasAssignment(
                productId = row[ProductExtrasAssignmentTable.productId].value,
                extrasId = row[ProductExtrasAssignmentTable.extrasId].value,
            )
        }
    }

    override fun getByProductId(productId: Long): List<ProductExtrasAssignment> {
        val assignment =
            ProductExtrasAssignmentTable
                .selectAll()
                .where { ProductExtrasAssignmentTable.productId eq productId }
                .map { row ->
                    ProductExtrasAssignment(
                        productId = row[ProductExtrasAssignmentTable.productId].value,
                        extrasId = row[ProductExtrasAssignmentTable.extrasId].value,
                    )
                }
        return assignment
    }

    override fun create(
        productId: Long,
        extrasId: Int,
    ): ProductExtrasAssignment {
        ProductExtrasAssignmentTable.insert {
            it[ProductExtrasAssignmentTable.productId] = productId
            it[ProductExtrasAssignmentTable.extrasId] = extrasId
        }
        return ProductExtrasAssignment(productId = productId, extrasId = extrasId)
    }

    override fun createIfNotExists(
        productId: Long,
        extrasId: Int,
    ): ProductExtrasAssignment? {
        val exists =
            ProductExtrasAssignmentTable
                .selectAll()
                .where {
                    (ProductExtrasAssignmentTable.productId eq productId) and
                        (ProductExtrasAssignmentTable.extrasId eq extrasId)
                }.count() > 0

        if (exists) return null

        ProductExtrasAssignmentTable.insert {
            it[ProductExtrasAssignmentTable.productId] = productId
            it[ProductExtrasAssignmentTable.extrasId] = extrasId
        }
        return ProductExtrasAssignment(productId = productId, extrasId = extrasId)
    }

    override fun delete(
        productId: Long,
        extrasId: Int,
    ): Boolean =
        ProductExtrasAssignmentTable.deleteWhere {
            (ProductExtrasAssignmentTable.productId eq productId) and
                (ProductExtrasAssignmentTable.extrasId eq extrasId)
        } > 0

    override fun deleteByProductIds(
        productIds: List<Long>,
        extrasId: Int,
    ) {
        if (productIds.isEmpty()) return
        ProductExtrasAssignmentTable.deleteWhere {
            (ProductExtrasAssignmentTable.productId inList productIds) and
                (ProductExtrasAssignmentTable.extrasId eq extrasId)
        }
    }

    override fun getUpdatedAfter(lastSyncTime: LocalDateTime): List<ProductExtrasAssignment> =
        ProductExtrasAssignmentTable
            .selectAll()
            .where {
                ProductExtrasAssignmentTable.updatedAt greater lastSyncTime
            }.map { row ->
                ProductExtrasAssignment(
                    productId = row[ProductExtrasAssignmentTable.productId].value,
                    extrasId = row[ProductExtrasAssignmentTable.extrasId].value,
                )
            }
}
