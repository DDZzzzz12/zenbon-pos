/**
 * Repository interface for Product data operations.
 *
 * Defines the contract for Product data access operations.
 * Returns plain entities; exceptions propagate naturally to the service layer.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.product.domain.repository

import org.example.project.core.domain.enums.product.ProductType
import org.example.project.features.product.domain.models.Product
import java.math.BigDecimal

/**
 * Repository interface for Product operations.
 *
 * Provides data access methods for product management including
 * CRUD operations, validation, and product-specific business logic.
 */
interface ProductRepository {
    /**
     * Retrieve all products with optional pagination and filtering.
     */
    fun getAllProducts(
        limit: Int? = null,
        offset: Int = 0,
        active: Boolean? = null,
    ): List<Product>

    /**
     * Retrieve active products filtered by category visibility scopes.
     */
    fun getProductsByCategoryVisibility(visibilityScopes: List<String>): List<Product>

    /**
     * Retrieve a product by its ID.
     */
    fun getProductById(id: Long): Product?

    /**
     * Retrieve a product by its number.
     */
    fun getProductByNumber(productNumber: String): Product?

    /**
     * Retrieve products by category with optional pagination.
     */
    fun getProductsByCategory(
        categoryId: Int,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Product>

    /**
     * Retrieve products by type with optional pagination.
     */
    fun getProductsByType(
        productType: ProductType,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Product>

    /**
     * Search products by name with optional pagination.
     */
    fun searchProductsByName(
        searchTerm: String,
        limit: Int? = null,
        offset: Int = 0,
        includeInactive: Boolean = false,
    ): List<Product>

    /**
     * Create a new product.
     */
    fun createProduct(
        productNumber: String,
        name: String,
        enName: String? = null,
        chName: String? = null,
        description: String? = null,
        price: BigDecimal,
        categoryId: Int,
        productType: ProductType,
        isActive: Boolean = true,
        allowCustomPrice: Boolean = false,
        imageUrl: String? = null,
        pfandTypeId: Long? = null,
    ): Product

    /**
     * Update an existing product.
     */
    fun updateProduct(
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
        allowCustomPrice: Boolean = false,
        imageUrl: String? = null,
        pfandTypeId: Long? = null,
    ): Product?

    /**
     * Delete a product.
     * @return "DELETED" if physically deleted, "INACTIVE" if soft-deleted
     */
    fun deleteProduct(id: Long): String

    /**
     * Get all active products with optional pagination.
     */
    fun getActiveProducts(
        limit: Int? = null,
        offset: Int = 0,
    ): List<Product>

    /**
     * Get active products by category with optional pagination.
     */
    fun getActiveProductsByCategory(
        categoryId: Int,
        limit: Int? = null,
        offset: Int = 0,
    ): List<Product>

    /**
     * Get products updated after the specified timestamp for synchronization.
     */
    fun getProductsUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<Product>
}
