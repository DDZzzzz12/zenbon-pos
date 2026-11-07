/**
 * Implementation of Product repository using Exposed ORM.
 *
 * Returns plain entities; exceptions propagate naturally to the service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.pfand.domain.models.PfandType
import org.example.project.features.product.database.tables.FavouriteProductTable
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductChoiceGroupTable
import org.example.project.features.product.database.tables.ProductExtrasAssignmentTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.product.domain.repository.ProductRepository
import org.example.project.features.promotion.database.tables.PromotionTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.*
import java.math.BigDecimal

/**
 * Exposed ORM implementation of ProductRepository.
 */
class ProductRepositoryImpl : ProductRepository {
    override fun getAllProducts(
        limit: Int?,
        offset: Int,
        active: Boolean?,
    ): List<Product> {
        // ORDER BY belongs in SQL: the service used to sort the mapped DTOs AFTER this method had
        // already applied LIMIT/OFFSET, which sorted each page in isolation and left the page
        // boundary to an unordered scan. category_id is not unique, so OFFSET paging also needs a
        // unique tiebreaker or equal-category rows shuffle between page queries and get duplicated
        // or skipped — same defect and same fix as StatisticsRepositoryImpl's paginated bills.
        var query =
            (
                active?.let { activeFilter ->
                    Product.find { ProductTable.isActive eq activeFilter }
                } ?: Product.all()
            ).orderBy(ProductTable.categoryId to SortOrder.ASC, ProductTable.id to SortOrder.ASC)

        // Apply offset
        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(start = offset.toLong())
        } else if (limit != null) {
            query = query.limit(limit)
        }

        return query.toList()
    }

    override fun getProductsByCategoryVisibility(visibilityScopes: List<String>): List<Product> =
        Product
            .find {
                (ProductTable.isActive eq true) and
                    (
                        ProductTable.categoryId inSubQuery
                            ProductCategoryTable.select(ProductCategoryTable.id).where {
                                ProductCategoryTable.visibility inList visibilityScopes
                            }
                    )
            }.orderBy(ProductTable.categoryId to SortOrder.ASC, ProductTable.id to SortOrder.ASC)
            .toList()

    override fun getProductById(id: Long): Product? = Product.findById(id)

    override fun getProductByNumber(productNumber: String): Product? =
        Product
            .find {
                ProductTable.productNumber eq productNumber
            }.firstOrNull()

    override fun getProductsByCategory(
        categoryId: Int,
        limit: Int?,
        offset: Int,
    ): List<Product> {
        ProductCategory.findById(categoryId)
            ?: throw NoSuchElementException("ProductCategory not found: $categoryId")

        // Ordered in SQL before LIMIT/OFFSET, same as getAllProducts. The filter pins category_id
        // to one value, so the unique id alone is the deterministic order OFFSET paging needs.
        var query =
            Product
                .find {
                    ProductTable.categoryId eq categoryId
                }.orderBy(ProductTable.id to SortOrder.ASC)

        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(start = offset.toLong())
        } else if (limit != null) {
            query = query.limit(limit)
        }

        return query.toList()
    }

    override fun getProductsByType(
        productType: ProductType,
        limit: Int?,
        offset: Int,
    ): List<Product> {
        // Spans categories, so it carries the same category_id + id ordering as getAllProducts.
        var query =
            Product
                .find {
                    ProductTable.productType eq productType
                }.orderBy(ProductTable.categoryId to SortOrder.ASC, ProductTable.id to SortOrder.ASC)

        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(start = offset.toLong())
        } else if (limit != null) {
            query = query.limit(limit)
        }

        return query.toList()
    }

    override fun searchProductsByName(
        searchTerm: String,
        limit: Int?,
        offset: Int,
        includeInactive: Boolean,
    ): List<Product> {
        val lowerTerm = searchTerm.lowercase()
        val maxResults = limit ?: 10
        // `offset` is applied by dropping from the assembled list below, so the fetch window has to
        // span the skipped rows too. Capping the fetch at `maxResults` and then dropping `offset`
        // from it returned nothing at all for every page past the first.
        val window = offset + maxResults

        // 1. Search by product number first. Ordered in SQL — the previous `.sortedBy` pulled every
        // match into the JVM to sort it, and the name tier below was not ordered at all.
        val byNumber =
            Product
                .find {
                    val base = ProductTable.productNumber.lowerCase() like "%$lowerTerm%"
                    if (includeInactive) base else base and (ProductTable.isActive eq true)
                }.orderBy(ProductTable.id to SortOrder.ASC)
                .limit(window)
                .toList()

        val results =
            if (byNumber.size >= window) {
                byNumber
            } else {
                // 2. Fill remaining slots with name matches (excluding already found).
                // byNumber came back short of the window, which means it is the COMPLETE set of number
                // matches — so excluding foundIds here cannot miss one that fell outside the window.
                val foundIds = byNumber.map { it.id.value }.toSet()
                val remaining = window - byNumber.size
                val byName =
                    Product
                        .find {
                            val base =
                                (ProductTable.name.lowerCase() like "%$lowerTerm%") and
                                    (ProductTable.id notInList foundIds)
                            if (includeInactive) base else base and (ProductTable.isActive eq true)
                        }.orderBy(ProductTable.id to SortOrder.ASC)
                        .limit(remaining)
                        .toList()

                byNumber + byName
            }

        // results holds at most `window` rows, so the drop leaves at most `maxResults`.
        return if (offset > 0) results.drop(offset) else results
    }

    override fun createProduct(
        productNumber: String,
        name: String,
        enName: String?,
        chName: String?,
        description: String?,
        price: BigDecimal,
        categoryId: Int,
        productType: ProductType,
        isActive: Boolean,
        allowCustomPrice: Boolean,
        imageUrl: String?,
        pfandTypeId: Long?,
    ): Product {
        if (productNumber.isBlank()) {
            throw IllegalArgumentException("productNumber is required")
        }

        if (name.isBlank()) {
            throw IllegalArgumentException("name is required")
        }

        if (price < BigDecimal.ZERO) {
            throw IllegalArgumentException("price must be non-negative")
        }

        // Verify category exists
        val category =
            ProductCategory.findById(categoryId)
                ?: throw NoSuchElementException("ProductCategory not found: $categoryId")

        // Check for duplicate product number
        val existingProduct =
            Product
                .find {
                    ProductTable.productNumber eq productNumber
                }.firstOrNull()

        if (existingProduct != null) {
            throw IllegalStateException("Duplicate product number: $productNumber")
        }

        val currentTimestamp = currentTimeStamp()

        return Product.new {
            this.productNumber = productNumber
            this.name = name
            this.enName = enName
            this.chName = chName
            this.description = description
            this.price = price
            this.category = category
            this.productType = productType
            this.isActive = isActive
            this.allowCustomPrice = allowCustomPrice
            this.imageUrl = imageUrl
            this.pfandType = pfandTypeId?.let { PfandType.findById(it) }
            this.updatedAt = currentTimestamp
        }
    }

    override fun updateProduct(
        id: Long,
        productNumber: String,
        name: String,
        enName: String?,
        chName: String?,
        description: String?,
        price: BigDecimal,
        categoryId: Int,
        productType: ProductType,
        isActive: Boolean,
        allowCustomPrice: Boolean,
        imageUrl: String?,
        pfandTypeId: Long?,
    ): Product? {
        if (productNumber.isBlank()) {
            throw IllegalArgumentException("productNumber is required")
        }

        if (name.isBlank()) {
            throw IllegalArgumentException("name is required")
        }

        if (price < BigDecimal.ZERO) {
            throw IllegalArgumentException("price must be non-negative")
        }

        val product = Product.findById(id) ?: return null

        // Verify category exists
        val category =
            ProductCategory.findById(categoryId)
                ?: throw NoSuchElementException("ProductCategory not found: $categoryId")

        // Check for duplicate product number (excluding current product)
        val existingProduct =
            Product
                .find {
                    (ProductTable.productNumber eq productNumber) and
                        (ProductTable.id neq id)
                }.firstOrNull()

        if (existingProduct != null) {
            throw IllegalStateException("Duplicate product number: $productNumber")
        }

        product.productNumber = productNumber
        product.name = name
        product.enName = enName
        product.chName = chName
        product.description = description
        product.price = price
        product.category = category
        product.productType = productType
        product.isActive = isActive
        product.allowCustomPrice = allowCustomPrice
        product.imageUrl = imageUrl
        product.pfandType = pfandTypeId?.let { PfandType.findById(it) }
        product.updatedAt = currentTimeStamp()

        return product
    }

    /**
     * Deletes a product, or deactivates it when history points at it (QA admin19 FAIL-B).
     *
     * History is anything that must keep resolving the product row: an order line, or a promotion
     * that targets it (promotions are soft-deleted, so even a retired one still holds the FK). Such a
     * product is only deactivated, and keeps its configuration, so reactivating it restores it as it was.
     *
     * Otherwise the product is hard-deleted, and its **configuration** goes with it: choice-group and
     * extras placements, the favourites ranking, and an option's link to it. These belong to the
     * product, not to the groups or extras, which survive and stay attached elsewhere. They are
     * removed explicitly because a fresh install's schema comes from Exposed, whose foreign keys
     * carry no `ON DELETE CASCADE` — the delete then failed with an FK violation and a 500, while an
     * upgraded install (Flyway, with CASCADE) silently succeeded.
     */
    override fun deleteProduct(id: Long): String {
        val product =
            Product.findById(id)
                ?: throw NoSuchElementException("Product not found: $id")

        if (isReferencedByHistory(id)) {
            product.isActive = false
            product.updatedAt = currentTimeStamp()
            return "INACTIVE"
        }
        detachConfiguration(id)
        product.delete()
        return "DELETED"
    }

    private fun isReferencedByHistory(id: Long): Boolean =
        !OrderItemTable
            .select(OrderItemTable.id)
            .where { OrderItemTable.productId eq id }
            .limit(1)
            .empty() ||
            !PromotionTable
                .select(PromotionTable.id)
                .where { PromotionTable.targetProductId eq id }
                .limit(1)
                .empty()

    private fun detachConfiguration(id: Long) {
        ProductChoiceGroupTable.deleteWhere { ProductChoiceGroupTable.productId eq id }
        ProductExtrasAssignmentTable.deleteWhere { ProductExtrasAssignmentTable.productId eq id }
        FavouriteProductTable.deleteWhere { FavouriteProductTable.product eq id }
        ProductExtrasTable.update({ ProductExtrasTable.linkedProductId eq id }) {
            it[ProductExtrasTable.linkedProductId] = null
        }
    }

    override fun getActiveProducts(
        limit: Int?,
        offset: Int,
    ): List<Product> {
        // Spans categories, so it carries the same category_id + id ordering as getAllProducts.
        var query =
            Product
                .find {
                    ProductTable.isActive eq true
                }.orderBy(ProductTable.categoryId to SortOrder.ASC, ProductTable.id to SortOrder.ASC)

        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(start = offset.toLong())
        } else if (limit != null) {
            query = query.limit(limit)
        }

        return query.toList()
    }

    override fun getActiveProductsByCategory(
        categoryId: Int,
        limit: Int?,
        offset: Int,
    ): List<Product> {
        ProductCategory.findById(categoryId)
            ?: throw NoSuchElementException("ProductCategory not found: $categoryId")

        // category_id is pinned by the filter, so id alone is the deterministic paging order.
        var query =
            Product
                .find {
                    (ProductTable.categoryId eq categoryId) and
                        (ProductTable.isActive eq true)
                }.orderBy(ProductTable.id to SortOrder.ASC)

        if (offset > 0) {
            query = query.limit(count = limit ?: Int.MAX_VALUE).offset(start = offset.toLong())
        } else if (limit != null) {
            query = query.limit(limit)
        }

        return query.toList()
    }

    override fun getProductsUpdatedAfter(lastSyncTime: LocalDateTime): List<Product> =
        Product
            .find {
                (ProductTable.updatedAt greater lastSyncTime) and
                    (ProductTable.productType neq ProductType.VOUCHER)
            }.toList()
}
