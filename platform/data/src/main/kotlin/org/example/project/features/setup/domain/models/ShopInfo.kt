package org.example.project.features.setup.domain.models

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/**
 * Shop Information entity and table definition using Exposed ORM.
 *
 * Represents shop/business information including contact details,
 * location, timing, and branding elements. Converted from JPA entity to
 * Exposed ORM for Kotlin multiplatform compatibility.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Exposed DAO entity class for shop information operations.
 *
 * Provides object-relational mapping functionality for database operations
 * including CRUD operations, queries, and relationship management.
 */
class ShopInfo(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ShopInfo>(ShopInfoTable)

    /** Shop business name */
    var shopName by ShopInfoTable.shopName

    /** Company name (can be different from shop name) */
    var companyName by ShopInfoTable.companyName

    /** Physical address of the shop */
    var shopAddress by ShopInfoTable.shopAddress

    /** City where the shop is located */
    var shopCity by ShopInfoTable.shopCity

    /** Postal/ZIP code */
    var zipCode by ShopInfoTable.zipCode

    /** Optional § 27a UStG USt-IdNr (audit STAM-8). */
    var ustIdNr by ShopInfoTable.ustIdNr

    /** Country where the shop is located */
    var shopCountry by ShopInfoTable.shopCountry

    /** Contact phone number */
    var shopPhone by ShopInfoTable.shopPhone

    /** Contact email address */
    var shopEmail by ShopInfoTable.shopEmail

    /** Timezone identifier */
    var shopTimezone by ShopInfoTable.shopTimezone

    /** Official website URL */
    var shopWebsite by ShopInfoTable.shopWebsite

    var shopTaxNumber by ShopInfoTable.shopTaxNumber

    var shopBankName by ShopInfoTable.shopBankName

    var shopBankBic by ShopInfoTable.shopBankBic

    var shopBankIBAN by ShopInfoTable.shopBankIBAN

    /** Whether restaurant mode (table management, floor plans) is enabled */
    var restaurantMode by ShopInfoTable.restaurantMode

    /** Retained for compatibility; no longer read since the kiosk screen was removed. */
    var kioskMode by ShopInfoTable.kioskMode

    /** URL path to the shop logo image */
    var logoUrl by ShopInfoTable.logoUrl

    /** Record creation timestamp */
    var createAt by ShopInfoTable.createAt

    /**
     * Validates the shop information data for business rules compliance.
     *
     * Ensures required fields are present and data format is correct
     * before database operations.
     *
     * @throws IllegalArgumentException if validation fails
     */
    fun validate() {
        require(shopName.isNotBlank()) { "Shop name cannot be blank" }

        // Validate email format if provided
        shopEmail?.let { email ->
            require(email.contains("@") && email.contains(".")) {
                "Invalid email format: $email"
            }
        }

        // Validate ZIP code is positive if provided
        zipCode.let { zip ->
            require(zip > 0) { "ZIP code must be positive: $zip" }
        }

        // Validate website URL format if provided
        shopWebsite?.let { website ->
            require(website.startsWith("http://") || website.startsWith("https://")) {
                "Website URL must start with http:// or https://"
            }
        }
    }

    /**
     * Returns a string representation of the shop information.
     *
     * Provides a readable format for logging and debugging purposes
     * while excluding sensitive information.
     *
     * @return Formatted string representation
     */
    override fun toString(): String =
        """
        ShopInfo(
            shopId=$id,
            shopName='$shopName',
            shopCity='$shopCity',
            shopCountry='$shopCountry',
            shopEmail='$shopEmail',
            shopTimezone='$shopTimezone',
            createAt=$createAt
        )
        """.trimIndent()
}
