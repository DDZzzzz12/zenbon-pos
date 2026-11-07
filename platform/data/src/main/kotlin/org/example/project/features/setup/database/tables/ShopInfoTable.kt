package org.example.project.features.setup.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for shop_info table.
 *
 * Defines the database schema structure with proper column types,
 * constraints, and naming conventions for shop information storage.
 */
object ShopInfoTable : LongIdTable("shop_info", "shop_id") {
    /** Shop business name */
    val shopName = varchar("shop_name", 255)

    /** Company name (can be different from shop name) */
    val companyName = varchar("company_name", 255).nullable()

    /** Physical address of the shop */
    val shopAddress = varchar("shop_address", 500)

    /** City where the shop is located */
    val shopCity = varchar("shop_city", 100)

    /** Postal/ZIP code */
    val zipCode = integer("zip_code")

    /** Optional § 27a UStG USt-IdNr (STNR and USTID are ALTERNATIVES in DSFinV-K — audit STAM-8). */
    val ustIdNr = varchar("ust_id_nr", 15).nullable()

    /** Country where the shop is located */
    val shopCountry = varchar("shop_country", 100)

    /** Contact phone number */
    val shopPhone = varchar("shop_phone", 50)

    /** TAX number */
    val shopTaxNumber = varchar("shop_tax_number", 50).nullable()

    val shopBankName = varchar("shop_bank_name", 50).nullable()
    val shopBankBic = varchar("shop_bank_bic", 50).nullable()
    val shopBankIBAN = varchar("shop_bank_iban", 50).nullable()

    /** Contact email address */
    val shopEmail = varchar("shop_email", 255).nullable()

    /** Timezone identifier (e.g., "America/New_York") */
    val shopTimezone = varchar("shop_timezone", 50).default("Europe/Berlin")

    /** Official website URL */
    val shopWebsite = varchar("shop_website", 500).nullable()

    /** Whether restaurant mode (table management, floor plans) is enabled */
    val restaurantMode = bool("restaurant_mode").default(true)

    /** Retained for compatibility; no longer read since the kiosk screen was removed. */
    val kioskMode = bool("kiosk_mode").default(false)

    /** URL path to the shop logo image */
    val logoUrl = varchar("logo_url", 500).nullable()

    /** Record creation timestamp */
    val createAt = datetime("create_at")
}
