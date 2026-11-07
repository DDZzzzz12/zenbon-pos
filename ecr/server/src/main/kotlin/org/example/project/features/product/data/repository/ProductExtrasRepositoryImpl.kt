/**
 * Implementation of ProductExtras repository using Exposed ORM.
 *
 * Returns plain entities; exceptions propagate naturally to the service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.product.database.tables.ProductExtrasAssignmentTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.ProductExtras
import org.example.project.features.product.domain.repository.ProductExtrasRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.select
import java.math.BigDecimal

/**
 * Exposed ORM implementation of ProductExtrasRepository.
 */
class ProductExtrasRepositoryImpl : ProductExtrasRepository {
    override fun getAllExtras(includeInactive: Boolean): List<ProductExtras> =
        if (includeInactive) {
            ProductExtras.all().toList()
        } else {
            ProductExtras.find { ProductExtrasTable.isActive eq true }.toList()
        }

    override fun getExtraById(id: Int): ProductExtras? = ProductExtras.findById(id)

    override fun getExtraByName(name: String): ProductExtras? =
        ProductExtras
            .find {
                ProductExtrasTable.name eq name
            }.firstOrNull()

    override fun createExtra(
        name: String,
        enName: String?,
        chName: String?,
        price: BigDecimal,
        isActive: Boolean,
    ): ProductExtras {
        if (name.isBlank()) {
            throw IllegalArgumentException("name is required")
        }

        if (price < BigDecimal.ZERO) {
            throw IllegalArgumentException("price must be non-negative")
        }

        // Check for duplicate name
        val existingExtra =
            ProductExtras
                .find {
                    ProductExtrasTable.name eq name
                }.firstOrNull()

        if (existingExtra != null) {
            throw IllegalStateException("Duplicate extra name: $name")
        }

        val currentTimestamp = currentTimeStamp()
        return ProductExtras.new {
            this.name = name
            this.enName = enName
            this.chName = chName
            this.price = price
            this.isActive = isActive
            this.updatedAt = currentTimestamp
        }
    }

    override fun updateExtra(
        id: Int,
        name: String,
        price: BigDecimal,
        isActive: Boolean,
        enName: String?,
        chName: String?,
    ): ProductExtras? {
        if (name.isBlank()) {
            throw IllegalArgumentException("name is required")
        }

        if (price < BigDecimal.ZERO) {
            throw IllegalArgumentException("price must be non-negative")
        }

        val extra = ProductExtras.findById(id) ?: return null

        // Check for duplicate name (excluding current extra)
        val existingExtra =
            ProductExtras
                .find {
                    (ProductExtrasTable.name eq name) and
                        (ProductExtrasTable.id neq id)
                }.firstOrNull()

        if (existingExtra != null) {
            throw IllegalStateException("Duplicate extra name: $name")
        }

        extra.name = name
        extra.enName = enName
        extra.chName = chName
        extra.price = price
        extra.isActive = isActive
        extra.updatedAt = currentTimeStamp()

        return extra
    }

    override fun deleteExtra(id: Int): String {
        val extra =
            ProductExtras.findById(id)
                ?: throw NoSuchElementException("ProductExtras not found: $id")

        val entityName = extra.name
        extra.isActive = false
        extra.updatedAt = currentTimeStamp()
        return entityName
    }

    override fun getActiveExtras(): List<ProductExtras> =
        ProductExtras
            .find {
                ProductExtrasTable.isActive eq true
            }.toList()

    override fun getExtrasByCategory(categoryId: Int): List<ProductExtras> {
        val extraIds =
            ProductExtrasAssignmentTable
                .innerJoin(ProductTable)
                .select(ProductExtrasAssignmentTable.extrasId)
                .where { ProductTable.categoryId eq categoryId }
                .map { it[ProductExtrasAssignmentTable.extrasId].value }
                .distinct()

        if (extraIds.isEmpty()) return emptyList()

        return ProductExtras
            .find {
                ProductExtrasTable.id inList extraIds
            }.toList()
    }

    override fun getExtrasUpdatedAfter(lastSyncTime: LocalDateTime): List<ProductExtras> =
        ProductExtras
            .find {
                ProductExtrasTable.updatedAt greater lastSyncTime
            }.toList()
}
