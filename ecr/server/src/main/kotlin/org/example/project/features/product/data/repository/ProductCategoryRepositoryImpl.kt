/**
 * Implementation of ProductCategory repository using Exposed ORM.
 *
 * Returns plain entities; exceptions propagate naturally to the service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.data.repository

import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.product.domain.models.SystemCategoryNames
import org.example.project.features.product.domain.repository.ProductCategoryRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import java.math.BigDecimal

/**
 * Exposed ORM implementation of ProductCategoryRepository.
 */
class ProductCategoryRepositoryImpl : ProductCategoryRepository {
    private companion object {
        /** `product_category.sort_position` is `numeric(8,5)`: absolute values must stay below 1000. */
        val SORT_POSITION_LIMIT = BigDecimal(1000)
    }

    private fun requireSortPositionInRange(sortOrder: BigDecimal) {
        require(sortOrder.abs() < SORT_POSITION_LIMIT) { "sortOrder must be between -999.99999 and 999.99999" }
    }

    override fun getAllCategories(includeInactive: Boolean): List<ProductCategory> =
        if (includeInactive) {
            ProductCategory.all().toList()
        } else {
            ProductCategory.find { ProductCategoryTable.isActive eq true }.toList()
        }

    override fun getCategoriesByVisibility(visibilityScopes: List<String>): List<ProductCategory> =
        ProductCategory
            .find {
                (ProductCategoryTable.isActive eq true) and
                    (ProductCategoryTable.visibility inList visibilityScopes)
            }.toList()

    override fun getCategoryById(id: Int): ProductCategory? = ProductCategory.findById(id)

    override fun getCategoryByName(name: String): ProductCategory? =
        ProductCategory
            .find {
                ProductCategoryTable.name eq name
            }.firstOrNull()

    override fun createCategory(
        id: Int?,
        name: String,
        enName: String?,
        chName: String?,
        description: String?,
        isActive: Boolean,
        sortOrder: BigDecimal?,
        visibility: String,
    ): ProductCategory {
        if (name.isBlank()) {
            throw IllegalArgumentException("name is required")
        }

        // Check for duplicate name
        val existingCategory =
            ProductCategory
                .find {
                    ProductCategoryTable.name eq name
                }.firstOrNull()

        if (existingCategory != null) {
            throw IllegalStateException("Duplicate category name: $name")
        }

        val existingId = if (id != null) ProductCategory.findById(id) else null

        sortOrder?.let(::requireSortPositionInRange)

        val sortPosition =
            if (sortOrder != null) {
                sortOrder
            } else {
                val lastCat =
                    ProductCategory
                        .find {
                            ProductCategoryTable.name notInList SystemCategoryNames.ALL
                        }.orderBy(ProductCategoryTable.sortPosition to SortOrder.DESC)
                        .firstOrNull()
                val next = lastCat?.sortPosition?.add(BigDecimal(1)) ?: BigDecimal(1)
                // Never reach the system categories' positions (numeric(8,5) overflows past 999.99999).
                next.min(BigDecimal(SystemCategoryNames.MAX_AUTO_SORT_POSITION))
            }

        val category =
            existingId?.apply {
                this.name = name
                this.enName = enName
                this.chName = chName
                this.description = description
                this.isActive = isActive
                this.sortPosition = sortPosition
                this.visibility = visibility
                this.updatedAt = currentTimeStamp()
            }
                ?: ProductCategory.new(id = id) {
                    this.name = name
                    this.enName = enName
                    this.chName = chName
                    this.description = description
                    this.isActive = isActive
                    this.sortPosition = sortPosition
                    this.visibility = visibility
                    this.updatedAt = currentTimeStamp()
                }

        return category
    }

    override fun updateCategory(
        id: Int,
        name: String,
        description: String?,
        isActive: Boolean,
        enName: String?,
        chName: String?,
        sortOrder: BigDecimal?,
        visibility: String?,
    ): ProductCategory? {
        if (name.isBlank()) {
            throw IllegalArgumentException("name is required")
        }

        val category = ProductCategory.findById(id) ?: return null

        // Check for duplicate name (excluding current category)
        val existingCategory =
            ProductCategory
                .find {
                    (ProductCategoryTable.name eq name) and
                        (ProductCategoryTable.id neq id)
                }.firstOrNull()

        if (existingCategory != null) {
            throw IllegalStateException("Duplicate category name: $name")
        }

        category.name = name
        category.enName = enName
        category.chName = chName
        category.description = description
        category.isActive = isActive

        if (sortOrder != null) {
            requireSortPositionInRange(sortOrder)
            category.sortPosition = sortOrder
        }

        if (visibility != null) {
            category.visibility = visibility
        }

        category.updatedAt = currentTimeStamp()

        return category
    }

    override fun deleteCategory(id: Int) {
        val category =
            ProductCategory.findById(id)
                ?: throw NoSuchElementException("ProductCategory not found: $id")

        category.isActive = false
        category.updatedAt = currentTimeStamp()
    }

    override fun countActiveProducts(categoryId: Int): Long = Product.count((ProductTable.categoryId eq categoryId) and (ProductTable.isActive eq true))

    override fun getActiveCategories(): List<ProductCategory> =
        ProductCategory
            .find {
                ProductCategoryTable.isActive eq true
            }.toList()

    override fun getCategoriesUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<ProductCategory> =
        ProductCategory
            .find {
                (ProductCategoryTable.updatedAt greater lastSyncTime) and
                    (ProductCategoryTable.name neq "VOUCHER")
            }.toList()

    override fun updateCategorySortPosition(
        categoryId: Long,
        newSortPosition: Double,
    ): ProductCategory? {
        require(newSortPosition.isFinite()) { "sortOrder must be a finite number" }
        val sortPosition = newSortPosition.toBigDecimal()
        requireSortPositionInRange(sortPosition)
        val category = ProductCategory.findById(categoryId.toInt()) ?: return null

        category.sortPosition = sortPosition
        category.updatedAt = currentTimeStamp()

        return category
    }
}
