/**
 * Product type enumeration for product categorization.
 *
 * Defines the main categories of products available in the restaurant
 * point-of-sale system for inventory management and menu organization.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.product

/**
 * Enumeration representing the type/category of a product.
 *
 * Used to classify products into major categories for menu organization,
 * inventory management, and reporting purposes.
 */
enum class ProductType {
    /** Food items including meals, appetizers, desserts, etc. */
    FOOD,

    /** Beverage items including alcoholic and non-alcoholic drinks */
    DRINKS,

    /** Retail items for sale */
    RETAIL,

    /** Service items */
    SERVICE,

    /** Gift vouchers and store credit */
    VOUCHER,

    /** Other miscellaneous items */
    OTHERS,
}
