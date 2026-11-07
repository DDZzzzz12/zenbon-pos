/**
 * Manager for shop information data storage in local preferences.
 *
 * Handles storing and retrieving shop information using the local
 * key-value preference system. Stores non-sensitive shop data for
 * client-side caching and offline access.
 *
 * @property keyValueStore The key-value store for preference persistence
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.database

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Manages shop information in local preferences.
 *
 * Provides methods to store and retrieve shop data using a key-value
 * preference system. Each shop property is stored with a prefixed key.
 */
@OptIn(ExperimentalTime::class)
class ShopDataManager(
    private val keyValueStore: KeyValueStore,
) {
    companion object {
        // Preference key prefixes for shop information
        private const val SHOP_ID = "shop_id"
        private const val SHOP_NAME = "shop_name"
        private const val COMPANY_NAME = "company_name"
        private const val SHOP_ADDRESS = "shop_address"
        private const val SHOP_CITY = "shop_city"
        private const val ZIP_CODE = "zip_code"
        private const val SHOP_COUNTRY = "shop_country"
        private const val SHOP_PHONE = "shop_phone"
        private const val SHOP_EMAIL = "shop_email"
        private const val SHOP_TIMEZONE = "shop_timezone"
        private const val SHOP_WEBSITE = "shop_website"
        private const val SHOP_LAST_SYNCED = "shop_last_synced"
        private const val RESTAURANT_MODE = "restaurant_mode"
    }

    /**
     * Stores complete shop information in local preferences.
     *
     * Saves all shop fields as individual key-value pairs for easy
     * retrieval and partial updates.
     *
     * @param shopId Shop ID
     * @param shopName Shop business name
     * @param companyName Company name (nullable)
     * @param shopAddress Physical address (nullable)
     * @param shopCity City (nullable)
     * @param zipCode Postal code (nullable)
     * @param shopCountry Country (nullable)
     * @param shopPhone Contact phone (nullable)
     * @param shopEmail Contact email (nullable)
     * @param shopTimezone Timezone identifier (nullable)
     * @param shopWebsite Website URL (nullable)
     */
    fun saveShopInfo(
        shopId: Long,
        shopName: String,
        companyName: String?,
        shopAddress: String?,
        shopCity: String?,
        zipCode: Int?,
        shopCountry: String?,
        shopPhone: String?,
        shopEmail: String?,
        shopTimezone: String?,
        shopWebsite: String?,
        restaurantMode: Boolean = true,
    ) {
        keyValueStore.setString(SHOP_ID, shopId.toString())
        keyValueStore.setString(SHOP_NAME, shopName)
        companyName?.let { keyValueStore.setString(COMPANY_NAME, it) }
        shopAddress?.let { keyValueStore.setString(SHOP_ADDRESS, it) }
        shopCity?.let { keyValueStore.setString(SHOP_CITY, it) }
        zipCode?.let { keyValueStore.setString(ZIP_CODE, it.toString()) }
        shopCountry?.let { keyValueStore.setString(SHOP_COUNTRY, it) }
        shopPhone?.let { keyValueStore.setString(SHOP_PHONE, it) }
        shopEmail?.let { keyValueStore.setString(SHOP_EMAIL, it) }
        shopTimezone?.let { keyValueStore.setString(SHOP_TIMEZONE, it) }
        shopWebsite?.let { keyValueStore.setString(SHOP_WEBSITE, it) }
        keyValueStore.setString(RESTAURANT_MODE, restaurantMode.toString())
        keyValueStore.setString(
            SHOP_LAST_SYNCED,
            Clock.System
                .now()
                .toEpochMilliseconds()
                .toString(),
        )
    }

    /**
     * Retrieves shop ID from local preferences.
     *
     * @return Shop ID or null if not set
     */
    fun getShopId(): Long? =
        keyValueStore
            .getString(SHOP_ID)
            ?.toLongOrNull()

    /**
     * Retrieves shop name from local preferences.
     *
     * @return Shop name or null if not set
     */
    fun getShopName(): String? = keyValueStore.getString(SHOP_NAME)

    /**
     * Retrieves company name from local preferences.
     *
     * @return Company name or null if not set
     */
    fun getCompanyName(): String? = keyValueStore.getString(COMPANY_NAME)

    /**
     * Retrieves shop address from local preferences.
     *
     * @return Shop address or null if not set
     */
    fun getShopAddress(): String? = keyValueStore.getString(SHOP_ADDRESS)

    /**
     * Retrieves shop city from local preferences.
     *
     * @return Shop city or null if not set
     */
    fun getShopCity(): String? = keyValueStore.getString(SHOP_CITY)

    /**
     * Retrieves ZIP code from local preferences.
     *
     * @return ZIP code or null if not set
     */
    fun getZipCode(): Int? =
        keyValueStore
            .getString(ZIP_CODE)
            ?.toIntOrNull()

    /**
     * Retrieves shop country from local preferences.
     *
     * @return Shop country or null if not set
     */
    fun getShopCountry(): String? = keyValueStore.getString(SHOP_COUNTRY)

    /**
     * Retrieves shop phone from local preferences.
     *
     * @return Shop phone or null if not set
     */
    fun getShopPhone(): String? = keyValueStore.getString(SHOP_PHONE)

    /**
     * Retrieves shop email from local preferences.
     *
     * @return Shop email or null if not set
     */
    fun getShopEmail(): String? = keyValueStore.getString(SHOP_EMAIL)

    /**
     * Retrieves shop timezone from local preferences.
     *
     * @return Shop timezone or null if not set
     */
    fun getShopTimezone(): String? = keyValueStore.getString(SHOP_TIMEZONE)

    /**
     * Retrieves shop website from local preferences.
     *
     * @return Shop website URL or null if not set
     */
    fun getShopWebsite(): String? = keyValueStore.getString(SHOP_WEBSITE)

    /**
     * Retrieves last sync timestamp for shop data.
     *
     * @return Last sync timestamp in milliseconds or null if never synced
     */

    /**
     * Retrieves restaurant mode setting from local preferences.
     *
     * @return True if restaurant mode is enabled, true by default
     */
    fun getRestaurantMode(): Boolean =
        keyValueStore
            .getString(RESTAURANT_MODE)
            ?.toBoolean() ?: true

    fun getLastSyncedAt(): Long? =
        keyValueStore
            .getString(SHOP_LAST_SYNCED)
            ?.toLongOrNull()

    /**
     * Retrieves all shop information as a map.
     *
     * @return Map of all shop preferences with their values
     */
    fun getAllShopInfo(): Map<String, String?> =
        mapOf(
            SHOP_ID to getShopId()?.toString(),
            SHOP_NAME to getShopName(),
            COMPANY_NAME to getCompanyName(),
            SHOP_ADDRESS to getShopAddress(),
            SHOP_CITY to getShopCity(),
            ZIP_CODE to getZipCode()?.toString(),
            SHOP_COUNTRY to getShopCountry(),
            SHOP_PHONE to getShopPhone(),
            SHOP_EMAIL to getShopEmail(),
            SHOP_TIMEZONE to getShopTimezone(),
            SHOP_WEBSITE to getShopWebsite(),
        )

    /**
     * Clears all shop information from local preferences.
     *
     * Removes all shop-related preference entries. Useful for logout
     * or when switching shops.
     */
    fun clearShopInfo() {
        keyValueStore.remove(SHOP_ID)
        keyValueStore.remove(SHOP_NAME)
        keyValueStore.remove(COMPANY_NAME)
        keyValueStore.remove(SHOP_ADDRESS)
        keyValueStore.remove(SHOP_CITY)
        keyValueStore.remove(ZIP_CODE)
        keyValueStore.remove(SHOP_COUNTRY)
        keyValueStore.remove(SHOP_PHONE)
        keyValueStore.remove(SHOP_EMAIL)
        keyValueStore.remove(SHOP_TIMEZONE)
        keyValueStore.remove(SHOP_WEBSITE)
        keyValueStore.remove(RESTAURANT_MODE)
        keyValueStore.remove(SHOP_LAST_SYNCED)
    }

    /**
     * Checks if shop information exists in local preferences.
     *
     * @return True if shop ID is set, false otherwise
     */
    fun hasShopInfo(): Boolean = getShopId() != null
}
